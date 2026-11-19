#!/usr/bin/env bash
# Unit tests for the parts we can run off-device.
# Usage: tests/run.sh
set -uo pipefail

ROOT=$(cd "$(dirname "$0")/.." && pwd)
FIX="$ROOT/tests/fixtures"
TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT

failures=0
pass() { echo "ok   $1"; }
fail() { echo "FAIL $1: $2"; failures=$((failures + 1)); }
expect() { # <name> <expected> <actual>
	if [ "$2" = "$3" ]; then pass "$1"; else fail "$1" "expected '$2', got '$3'"; fi
}

# otto-remote's folder (the sound's choice lives there): never this machine's.
export OTTO_STATE="$TMP/otto-state"
mkdir -p "$OTTO_STATE"
# shellcheck source=iso/otto/init-hooks.sh
source "$ROOT/iso/otto/init-hooks.sh"
export OTTO_KMSG=/dev/null # keep test logs out of this machine's kernel log
# otto_detach moves its child to the root cgroup: never this machine's.
export OTTO_CGROUP_PROCS="$TMP/cgroup.procs"

# --- HDMI detection ---------------------------------------------------------

expect "hdmi: real Acer dump, TV on pcm 3" "pcmC0D3p" \
	"$(otto_parse_hdmi_jacks 0 <"$FIX/acer-hm77-hdmi-on.mixer.txt")"

expect "hdmi: same dump with the TV unplugged" "" \
	"$(sed "/HDMI\/DP,pcm=3 Jack/,/: values=/ s/values=on/values=off/" \
		"$FIX/acer-hm77-hdmi-on.mixer.txt" | otto_parse_hdmi_jacks 0)"

expect "hdmi: picks the connected port, not the first one" "pcmC1D7p" \
	"$(otto_parse_hdmi_jacks 1 <<'EOF'
numid=1,iface=CARD,name='HDMI/DP,pcm=3 Jack'
  ; type=BOOLEAN,access=r-------,values=1
  : values=off
numid=2,iface=CARD,name='HDMI/DP,pcm=7 Jack'
  ; type=BOOLEAN,access=r-------,values=1
  : values=on
numid=3,iface=CARD,name='HDMI/DP,pcm=8 Jack'
  ; type=BOOLEAN,access=r-------,values=1
  : values=on
EOF
)"

expect "hdmi: analog jacks are ignored" "" \
	"$(otto_parse_hdmi_jacks 0 <<'EOF'
numid=21,iface=CARD,name='Headphone Jack'
  ; type=BOOLEAN,access=r-------,values=1
  : values=on
EOF
)"

# Multi-card machine (AMD/Nvidia put HDMI audio on its own card):
# card 0 is analog only, card 1 has the TV.
cat >"$TMP/cards" <<'EOF'
 0 [PCH            ]: HDA-Intel - HDA Intel PCH
                      HDA Intel PCH at 0xf7f10000 irq 31
 1 [HDMI           ]: HDA-Intel - HDA ATI HDMI
                      HDA ATI HDMI at 0xf7e60000 irq 32
EOF
# shellcheck disable=SC2329  # called by otto_find_hdmi_pcm
alsa_amixer() { # mock: alsa_amixer -c <card> contents
	if [ "$2" = 1 ]; then
		printf "%s\n" "numid=9,iface=CARD,name='HDMI/DP,pcm=3 Jack'" \
			"  ; type=BOOLEAN,access=r-------,values=1" "  : values=on"
	fi
}
expect "hdmi: finds the TV on the second sound card" "pcmC1D3p" \
	"$(OTTO_ASOUND_CARDS="$TMP/cards" otto_find_hdmi_pcm)"

# --- post_init end to end, with Android's commands mocked ---------------------

# The console Otto is drawn on, and the drawing. Not a first boot, unless
# a test says so.
export OTTO_CONSOLE="$TMP/console" OTTO_ART="$ROOT/art/brand/otto-ascii.txt" OTTO_POST_INST="$TMP/post_inst_complete"
: >"$OTTO_CONSOLE"
touch "$OTTO_POST_INST"
# The two lines a first boot shows under Otto, centered on 170 columns.
first_caption="$(printf '%65s%s' '' 'Preparando o Otto TV pela primeira vez.')|$(printf '%72s%s' '' 'Isso leva alguns minutos.')"

# shellcheck disable=SC2329  # mocks, called by the hooks under test
{
	setprop() { echo "setprop $1 $2" >>"$TMP/props"; }
	getprop() { :; }
	set_prop_if_empty() { setprop "$1" "$2"; }
	log() { :; }
	setsid() { :; } # otto_mark_setup_done's background job
}
: >"$TMP/props"
OTTO_ASOUND_CARDS="$TMP/cards" post_init
expect "post_init: routes audio, sets the language and mirrors to the TV unasked" \
	"setprop persist.sys.locale pt-BR|setprop persist.sys.display.enable_on_connect.external true|setprop otto.audio.hdmi pcmC1D3p|setprop hal.audio.out.hdmi pcmC1D3p|setprop hal.audio.primary.hdmi 1" \
	"$(paste -sd'|' "$TMP/props")"

# The quick panel sent the sound to the laptop: it stays there, and the
# TV's output is still known for the panel to switch back to.
echo notebook >"$OTTO_STATE/audio"
: >"$TMP/props"
OTTO_ASOUND_CARDS="$TMP/cards" otto_setup_audio
expect "audio: sound kept on the laptop when the panel chose it (0, not empty)" \
	"1|setprop otto.audio.hdmi pcmC1D3p|setprop hal.audio.out.hdmi pcmC1D3p|setprop hal.audio.primary.hdmi 0" \
	"$?|$(paste -sd'|' "$TMP/props")"
: >"$TMP/props"
OTTO_ASOUND_CARDS=/nonexistent otto_setup_audio
expect "audio: on the laptop with no TV found either" \
	"1|setprop hal.audio.primary.hdmi 0" "$?|$(paste -sd'|' "$TMP/props")"
echo tv >"$OTTO_STATE/audio"
: >"$TMP/props"
OTTO_ASOUND_CARDS="$TMP/cards" otto_setup_audio
expect "audio: back on the TV when the panel chose it" \
	"0|setprop otto.audio.hdmi pcmC1D3p|setprop hal.audio.out.hdmi pcmC1D3p|setprop hal.audio.primary.hdmi 1" \
	"$?|$(paste -sd'|' "$TMP/props")"
rm -f "$OTTO_STATE/audio"

: >"$TMP/props"
OTTO_AUDIO=off OTTO_ASOUND_CARDS="$TMP/cards" post_init
expect "post_init: OTTO_AUDIO=off leaves audio alone" \
	"setprop persist.sys.locale pt-BR|setprop persist.sys.display.enable_on_connect.external true" \
	"$(paste -sd'|' "$TMP/props")"

: >"$TMP/props"
OTTO_MIRROR=ask otto_setup_mirror
expect "mirror: OTTO_MIRROR=ask brings back Android's question" \
	"setprop persist.sys.display.enable_on_connect.external false" "$(cat "$TMP/props")"
unset -f alsa_amixer setprop getprop set_prop_if_empty log setsid

# Otto redrawn once the GPU driver has the screen: 1366x768 in 8x16, as the
# initrd centers it.
echo 1366,768 >"$TMP/fb_size"
console() { sed 's/^\x1b\[2J\x1b\[H\x1b\[?25l//' "$OTTO_CONSOLE"; }
: >"$OTTO_CONSOLE"
OTTO_FB_SIZE="$TMP/fb_size" otto_console_art
expect "console: Otto redrawn 14 lines down, 47 spaces in" \
	"14|$(printf '%47s%s' '' "$(head -n 1 "$OTTO_ART")")|20" \
	"$(console | awk 'NF { print NR - 1; exit }')|$(console | awk 'NF' | head -n 1)|$(console | grep -c .)"

# A first boot: the same two lines as the initrd's under him, and "Almost
# ready" for when the boot animation ends.
# shellcheck disable=SC2329  # mocks, called by post_init
{
	otto_show_preparing() { echo preparing >>"$TMP/calls"; }
	set_prop_if_empty() { :; }
	otto_setup_mirror() { :; }
	otto_setup_audio() { :; }
	stty() { echo "48 170"; }
	log() { :; }
}
: >"$TMP/calls"
: >"$OTTO_CONSOLE"
OTTO_POST_INST="$TMP/none" post_init
expect "first boot: the initrd's two lines, and Almost ready" \
	"12|$first_caption|preparing" \
	"$(console | awk 'NF { print NR - 1; exit }')|$(console | tail -n 2 | paste -sd'|')|$(cat "$TMP/calls")"
: >"$TMP/calls"
: >"$OTTO_CONSOLE"
post_init
expect "first boot: nothing of it on later boots" "20|" "$(console | grep -c .)|$(cat "$TMP/calls")"
: >"$TMP/calls"
OTTO_POST_INST="$TMP/none" OTTO_SETUP=wizard post_init
expect "first boot: no Almost ready over upstream's wizard" "" "$(cat "$TMP/calls")"
unset -f otto_show_preparing set_prop_if_empty otto_setup_mirror otto_setup_audio stty log
# shellcheck source=iso/otto/init-hooks.sh
source "$ROOT/iso/otto/init-hooks.sh"

# "Almost ready" itself, with Android's commands as stubs: shown when the
# boot animation ends, told to go once boot complete sets the property.
stubs="$TMP/prep-bin"
mkdir -p "$stubs"
cat >"$stubs/getprop" <<'STUB'
#!/bin/sh
cat "$PREP/$1" 2>/dev/null
STUB
cat >"$stubs/am" <<'STUB'
#!/bin/sh
echo "am $1 $3" >>"$PREP/log"
STUB
printf '#!/bin/sh\n:\n' >"$stubs/log"
printf '#!/bin/sh\nexec /bin/sleep 0.05\n' >"$stubs/sleep"
chmod +x "$stubs"/*
# Usage: preparing <ready before the animation ends: 0|1> (prints what ran)
preparing() {
	export PREP="$TMP/prep"
	rm -rf "$PREP"
	mkdir -p "$PREP"
	echo "$1" >"$PREP/otto.boot.ready"
	(
		# shellcheck disable=SC2329  # mock, called by otto_detach
		setsid() { "$@"; }
		PATH="$stubs:$PATH" OTTO_CGROUP_PROCS=/dev/null otto_show_preparing
		/bin/sleep 0.3
		echo 1 >"$PREP/service.bootanim.exit"
		/bin/sleep 0.3
		echo 1 >"$PREP/otto.boot.ready"
		wait
	)
	paste -sd'|' "$PREP/log" 2>/dev/null
}
expect "almost ready: shown after the boot animation, closed when boot complete is done" \
	"am start app.ottotv.connect/.PreparingActivity|am broadcast app.ottotv.connect.READY|am broadcast app.ottotv.connect.READY|am broadcast app.ottotv.connect.READY" \
	"$(preparing 0)"
expect "almost ready: not shown when boot complete was quicker" "" "$(preparing 1)"

# --- On-screen keyboard ---------------------------------------------------------

# Device list modeled on the Acer: device names and key sets from its
# `getevent -p`, bitmaps in the kernel's /proc/bus/input/devices format.
expect "keyboards: laptop keyboard found, hotkeys and touchpad ignored" \
	"AT Translated Set 2 keyboard" \
	"$(otto_parse_keyboards <"$FIX/input-devices-laptop.txt")"

cat "$FIX/input-devices-laptop.txt" - >"$TMP/devices" <<'EOF'
I: Bus=0003 Vendor=046d Product=c31c Version=0110
N: Name="Logitech USB Keyboard"
H: Handlers=sysrq kbd event14 leds
B: EV=120013
B: KEY=1000000000007 ff9f207ac14057ff febeffdfffefffff fffffffffffffffe

I: Bus=0006 Vendor=0000 Product=0000 Version=0001
N: Name="Otto Remote Keyboard"
H: Handlers=sysrq kbd event15
B: EV=3
B: KEY=1000000000007 ff9f207ac14057ff febeffdfffefffff fffffffffffffffe

EOF
expect "keyboards: USB keyboard found, our virtual remote skipped" \
	"AT Translated Set 2 keyboard|Logitech USB Keyboard" \
	"$(otto_parse_keyboards <"$TMP/devices" | paste -sd'|')"

grep -v -e '^B: KEY=402000000' "$FIX/input-devices-laptop.txt" >"$TMP/no-keyboard"
expect "keyboards: none on a remote-only setup" "" \
	"$(otto_parse_keyboards <"$TMP/no-keyboard")"

# shellcheck disable=SC2329  # mocks, called by otto_setup_ime
{
	ime() {
		echo "ime $*" >>"$TMP/calls"
		[ "$1" = list ] && echo "$OTTO_HARD_IME"
		return 0
	}
	settings() { echo "settings $*" >>"$TMP/calls"; }
	log() { :; }
}
: >"$TMP/calls"
OTTO_INPUT_DEVICES="$FIX/input-devices-laptop.txt" otto_setup_ime
expect "ime: physical keyboard switches to our keyboard" \
	"ime list -a -s|ime enable $OTTO_HARD_IME|ime set $OTTO_HARD_IME|settings put secure show_ime_with_hard_keyboard 0" \
	"$(paste -sd'|' "$TMP/calls")"

: >"$TMP/calls"
OTTO_INPUT_DEVICES="$TMP/no-keyboard" otto_setup_ime
expect "ime: no physical keyboard keeps the TV keyboard" "" "$(cat "$TMP/calls")"

: >"$TMP/calls"
OTTO_IME=tv OTTO_INPUT_DEVICES="$FIX/input-devices-laptop.txt" otto_setup_ime
expect "ime: OTTO_IME=tv keeps the TV keyboard" "" "$(cat "$TMP/calls")"
unset -f ime settings log

# --- Phone remote start-up (Android-side hook) ---------------------------------------

mkdir -p "$TMP/otto/remote" "$TMP/state"
echo 'fake binary' >"$TMP/otto/remote/otto-remote"
# shellcheck disable=SC2329  # mocks, called by otto_start_remote
{
	setsid() { echo "setsid $1 $2 ... $4 $5" >>"$TMP/calls"; }
	log() { :; }
}
: >"$TMP/calls"
OTTO_STATE="$TMP/state" OTTO_DIR="$TMP/otto" otto_start_remote
wait # the hook backgrounds setsid
expect "remote: copied out of /boot and started under setsid" \
	"fake binary|setsid sh -c ... $TMP/state/otto-remote $TMP/state" \
	"$(cat "$TMP/state/otto-remote")|$(cat "$TMP/calls")"
: >"$TMP/calls"
if OTTO_STATE="$TMP/state" OTTO_DIR="$TMP/missing" otto_start_remote; then
	fail "remote: missing binary is reported" "otto_start_remote succeeded"
else
	pass "remote: missing binary is reported"
fi
unset -f setsid log

# --- Background jobs and first-run setup -------------------------------------------

# init kills init.sh's cgroup as soon as init.sh exits, so the child must be
# out of it before otto_detach returns, not whenever the child gets to it.
# shellcheck disable=SC2329  # mock, called by otto_detach
setsid() { sleep 0.3; echo "$BASHPID" >"$TMP/child"; }
rm -f "$TMP/child" "$TMP/cgroup.procs"
otto_detach 'true'
moved=$(cat "$TMP/cgroup.procs" 2>/dev/null)
started=$([ -e "$TMP/child" ] && echo yes || echo no)
wait
expect "detach: child leaves init.sh's cgroup before we return" \
	"no|$(cat "$TMP/child")" "$started|$moved"
unset -f setsid

# shellcheck disable=SC2329  # mocks, called by otto_setup_done
{
	settings() {
		case "$1" in
		get) echo "$SETTINGS_VALUE" ;;
		put) echo "put $3 $4" >>"$TMP/calls" ;;
		esac
	}
	log() { :; }
}
: >"$TMP/calls"
SETTINGS_VALUE=0 otto_setup_done
expect "setup: marked as done at boot complete, and named Otto TV" \
	"put device_provisioned 1|put user_setup_complete 1|put tv_user_setup_complete 1|put device_name Otto TV|put bluetooth_name Otto TV" \
	"$(paste -sd'|' "$TMP/calls")"
: >"$TMP/calls"
SETTINGS_VALUE=1 otto_setup_done
expect "setup: left alone when already done" "" "$(cat "$TMP/calls")"
: >"$TMP/calls"
SETTINGS_VALUE=0 OTTO_SETUP=wizard otto_setup_done
expect "setup: OTTO_SETUP=wizard leaves it to the wizard" "" "$(cat "$TMP/calls")"
unset -f settings log

# Boot complete: the early call does the work once; post_bootcomplete only
# steps in when upstream's init.sh changed and the call wasn't added.
# shellcheck disable=SC2329  # mocks, called by the hooks under test
{
	otto_setup_mirror() { echo mirror >>"$TMP/calls"; }
	otto_setup_done() { echo setup >>"$TMP/calls"; }
	otto_setup_ime() { echo ime >>"$TMP/calls"; }
	otto_start_remote() { echo remote >>"$TMP/calls"; [ -z "${REMOTE_FAILS:-}" ]; }
	otto_wait_for_apps() { echo wait >>"$TMP/calls"; }
	otto_setup_home() { echo home >>"$TMP/calls"; }
	otto_precompile() { echo compile >>"$TMP/calls"; }
	otto_offer_pairing() { echo pairing >>"$TMP/calls"; }
	setprop() { echo "$1=$2" >>"$TMP/calls"; }
	getprop() { echo pcmC0D3p; }
	log() { :; }
}
: >"$TMP/calls"
(otto_bootcomplete_early && post_bootcomplete)
expect "bootcomplete: early call does the work, post_bootcomplete doesn't repeat it" \
	"mirror|setup|remote|wait|home|compile|ime|pairing|otto.boot.ready=1" "$(paste -sd'|' "$TMP/calls")"
: >"$TMP/calls"
(post_bootcomplete)
expect "bootcomplete: post_bootcomplete steps in without the early call" \
	"mirror|setup|remote|wait|home|compile|ime|pairing|otto.boot.ready=1" "$(paste -sd'|' "$TMP/calls")"
: >"$TMP/calls"
(REMOTE_FAILS=1 otto_bootcomplete_early)
expect "bootcomplete: no pairing screen when the remote didn't start" \
	"mirror|setup|remote|wait|home|compile|ime|otto.boot.ready=1" "$(paste -sd'|' "$TMP/calls")"

# The TV woke up late: boot complete routes the sound and restarts
# audioserver, unless the quick panel chose the laptop.
# shellcheck disable=SC2329  # mocks, called by post_bootcomplete
{
	alsa_amixer() {
		[ "$2" = 1 ] && printf "%s\n" "numid=9,iface=CARD,name='HDMI/DP,pcm=3 Jack'" \
			"  ; type=BOOLEAN,access=r-------,values=1" "  : values=on"
	}
	getprop() { :; }
	setprop() { echo "$1=$2" >>"$TMP/calls"; }
	pkill() { echo "pkill $1" >>"$TMP/calls"; }
}
: >"$TMP/calls"
(OTTO_EARLY_DONE=1 OTTO_ASOUND_CARDS="$TMP/cards" post_bootcomplete)
expect "bootcomplete: a TV found late gets the sound" \
	"otto.audio.hdmi=pcmC1D3p|hal.audio.out.hdmi=pcmC1D3p|hal.audio.primary.hdmi=1|pkill audioserver" \
	"$(paste -sd'|' "$TMP/calls")"
echo notebook >"$OTTO_STATE/audio"
: >"$TMP/calls"
(OTTO_EARLY_DONE=1 OTTO_ASOUND_CARDS="$TMP/cards" post_bootcomplete)
expect "bootcomplete: ...but not when the sound was sent to the laptop" \
	"otto.audio.hdmi=pcmC1D3p|hal.audio.out.hdmi=pcmC1D3p|hal.audio.primary.hdmi=0" "$(paste -sd'|' "$TMP/calls")"
rm -f "$OTTO_STATE/audio"
unset -f otto_setup_mirror otto_setup_ime otto_start_remote otto_wait_for_apps otto_setup_home otto_precompile otto_offer_pairing setprop getprop pkill log alsa_amixer
# shellcheck source=iso/otto/init-hooks.sh
source "$ROOT/iso/otto/init-hooks.sh"

# Home screen: ours, with its two permissions, unless a launcher the user
# installed was picked.
# shellcheck disable=SC2329  # mocks, called by otto_setup_home
{
	appops() { echo "appops $*" >>"$TMP/calls"; }
	cmd() {
		case "$2" in
		resolve-activity) printf 'priority=0 preferredOrder=0 match=0x108000\n%s\n' "$HOME_NOW" ;;
		*) echo "cmd $*" >>"$TMP/calls" ;;
		esac
	}
	pm() { echo "package:com.spocky.projengmenu"; echo "package:com.stremio.one"; }
	log() { :; }
}
perms="appops set app.ottotv.connect GET_USAGE_STATS allow|appops set app.ottotv.connect SYSTEM_ALERT_WINDOW allow"
sethome="cmd package set-home-activity app.ottotv.connect/.home.HomeActivity"
for now in org.lineageos.tv.launcher/.MainActivity android/com.android.internal.app.ResolverActivity com.android.tv.settings/.FallbackHome ""; do
	: >"$TMP/calls"
	HOME_NOW="$now" otto_setup_home
	expect "home: ours over '${now:-nothing}'" "$perms|$sethome" "$(paste -sd'|' "$TMP/calls")"
done
: >"$TMP/calls"
HOME_NOW=app.ottotv.connect/.home.HomeActivity otto_setup_home
expect "home: already ours, only the permissions" "$perms" "$(paste -sd'|' "$TMP/calls")"
: >"$TMP/calls"
HOME_NOW=com.spocky.projengmenu/.ui.home.MainActivity otto_setup_home
expect "home: a launcher the user installed stays" "$perms" "$(paste -sd'|' "$TMP/calls")"
: >"$TMP/calls"
OTTO_LAUNCHER=upstream HOME_NOW=org.lineageos.tv.launcher/.MainActivity otto_setup_home
expect "home: OTTO_LAUNCHER=upstream leaves the home screen alone" "$perms" "$(paste -sd'|' "$TMP/calls")"
unset -f appops cmd pm log
# shellcheck source=iso/otto/init-hooks.sh
source "$ROOT/iso/otto/init-hooks.sh"

# Installed systems unlock the user a few seconds after boot completes, and
# our app is invisible until then.
# shellcheck disable=SC2329  # mocks, called by otto_wait_for_apps
{
	ime() {
		echo x >>"$TMP/ime-calls"
		[ "$(wc -l <"$TMP/ime-calls")" -gt "$LOCKED_FOR" ] && echo "$OTTO_HARD_IME"
		echo com.android.inputmethod.leanback/.service.LeanbackImeService
	}
	sleep() { :; }
	log() { echo "$3" >>"$TMP/calls"; }
}
: >"$TMP/calls"
: >"$TMP/ime-calls"
LOCKED_FOR=0 otto_wait_for_apps
expect "apps: live system, no wait" "1|" "$(wc -l <"$TMP/ime-calls")|$(cat "$TMP/calls")"
: >"$TMP/ime-calls"
LOCKED_FOR=4 otto_wait_for_apps
expect "apps: installed system waits for the unlock" "apps: visible after 4s (user unlocked)" "$(cat "$TMP/calls")"
: >"$TMP/calls"
: >"$TMP/ime-calls"
if LOCKED_FOR=999 OTTO_UNLOCK_WAIT=3 otto_wait_for_apps; then
	fail "apps: gives up eventually" "returned success"
else
	expect "apps: gives up eventually" "apps: ours still not visible after 3s" "$(cat "$TMP/calls")"
fi
unset -f ime sleep log
# Restore the real one for later tests.
# shellcheck source=iso/otto/init-hooks.sh
source "$ROOT/iso/otto/init-hooks.sh"

# --- GRUB menu ----------------------------------------------------------------

if PRODUCT_NAME="Otto TV" VERSION="0.1.0" ISO_LABEL="OTTO_TV" \
	BOOT_TIMEOUT=5 EXTRA_KERNEL_ARGS="SET_TZ_LOCATION=America/Sao_Paulo" \
	"$ROOT/tools/render-grub.sh" "$ROOT/iso/boot/grub/grub.cfg.in" "$TMP/grub.cfg" 2>/dev/null; then
	pass "grub: template renders"
else
	fail "grub: template renders" "render-grub.sh failed"
fi
# shellcheck disable=SC2016  # a literal $KERNEL_ARGS
expect "grub: every entry finds the boot media by our label" "3" \
	"$(grep -c '^\s*linux .*\$KERNEL_ARGS' "$TMP/grub.cfg")"
expect "grub: the label is in the kernel arguments" "1" \
	"$(grep -c 'ROOT=LABEL=OTTO_TV ' "$TMP/grub.cfg")"
expect "grub: the menu, in order" \
	"Otto TV|Instalar o Otto TV neste computador|Otto TV em modo de vídeo seguro|Configurações do computador (UEFI)" \
	"$(sed -n 's/^\s*menuentry "\([^"]*\)".*/\1/p' "$TMP/grub.cfg" | paste -sd'|')"
expect "grub: firmware settings only on UEFI" "if is_efi; then" \
	"$(grep -B1 'Configurações do computador' "$TMP/grub.cfg" | head -n 1)"
expect "grub: no upstream names left" "" \
	"$(grep -i -o 'lineage\|bliss\|tv box\|android-x86' "$TMP/grub.cfg")"

# The theme only names files and fonts that exist.
theme="$ROOT/iso/boot/grub/themes/otto"
missing=""
while read -r f; do
	[ -f "$theme/$f" ] || missing="$missing $f"
done < <(sed -n 's/^\s*file = "\(.*\)"/\1/p' "$theme/theme.txt")
while read -r style; do
	for part in nw n ne w c e sw s se; do
		[ -f "$theme/$style$part.png" ] || missing="$missing $style$part.png"
	done
done < <(sed -n 's/^\s*\(item\|selected_item\)_pixmap_style = "\(.*\)\*\.png"/\2/p' "$theme/theme.txt")
expect "grub theme: every image exists" "" "$missing"
fonts=$(for f in "$theme"/*.pf2; do strings "$f" | sed -n '/^PFF2NAME$/{n;p;q}'; done | sort -u | paste -sd'|')
used=$(sed -n 's/.*font = "\(.*\)"/\1/p; s/^terminal-font: "\(.*\)"/\1/p' "$theme/theme.txt" | sort -u | paste -sd'|')
expect "grub theme: the fonts it names are the ones it ships" "$fonts" "$used"

# --- Boot screen (initrd) -------------------------------------------------------

cp "$FIX/upstream-initrd-init" "$TMP/init"
if python3 "$ROOT/tools/patch_initrd_init.py" "$TMP/init"; then
	pass "initrd: patch applies to upstream's /init"
else
	fail "initrd: patch applies to upstream's /init" "patch_initrd_init.py failed"
fi
if dash -n "$TMP/init" 2>/dev/null || sh -n "$TMP/init"; then
	pass "initrd: patched /init is valid shell"
else
	fail "initrd: patched /init is valid shell" "syntax error"
fi
# shellcheck disable=SC2016  # a literal $dev
expect "initrd: no upstream logo or chatter left" "0" \
	"$(grep -c -e '#\*\*\*' -e 'WARNING: NTFS' -e 'Detecting Android-x86' -e 'Scanning \$dev' -e 'console shell' "$TMP/init")"
expect "initrd: the kernel command line isn't printed" "1" \
	"$(grep -c 'if ! grep -q androidboot.slot_suffix' "$TMP/init")"
if python3 "$ROOT/tools/patch_initrd_init.py" "$TMP/init" 2>/dev/null; then
	fail "initrd: a changed upstream stops the build" "patched twice without complaint"
else
	pass "initrd: a changed upstream stops the build"
fi

# text_art centers Otto on a 170x48 console (1366x768 with the 8x16 font).
# Usage: initrd_art [VAR=value...]
initrd_art() (
	# shellcheck disable=SC2329  # mocks
	clear() { :; }
	# shellcheck disable=SC2329
	stty() { echo "48 170"; }
	eval "$(sed -n '/^text_art() {/,/^}/p' "$TMP/init")"
	# shellcheck disable=SC2030  # meant for this subshell only
	export OTTO_ART="$ROOT/art/brand/otto-ascii.txt" OTTO_DATA="$TMP/art-data" OTTO_CMDLINE="$TMP/art-cmdline"
	for v in "$@"; do export "${v?}"; done
	text_art
)
mkdir -p "$TMP/art-data/vendor"
touch "$TMP/art-data/vendor/post_inst_complete"
echo "quiet" >"$TMP/art-cmdline"
art=$(initrd_art)
expect "initrd: Otto starts 14 lines down" "14" \
	"$(printf '%s\n' "$art" | awk 'NF { print NR - 1; exit }')"
expect "initrd: Otto is centered (47 more spaces)" \
	"$(printf '%47s%s' '' "$(head -n 1 "$ROOT/art/brand/otto-ascii.txt")")" \
	"$(printf '%s\n' "$art" | sed -n 15p)"
expect "initrd: no caption once the first boot is done" "20" "$(printf '%s\n' "$art" | grep -c .)"
# First boot: two lines under Otto, everything 2 lines higher.
rm "$TMP/art-data/vendor/post_inst_complete"
art=$(initrd_art)
expect "initrd: first boot says it takes minutes" "12|$first_caption" \
	"$(printf '%s\n' "$art" | awk 'NF { print NR - 1; exit }')|$(printf '%s\n' "$art" | tail -n 2 | paste -sd'|')"
expect "initrd: the live system always starts from scratch" "22" \
	"$(echo "androidboot.live=true quiet" >"$TMP/art-cmdline"; initrd_art OTTO_DATA="$TMP/none" | grep -c .)"
expect "initrd: not the installer" "20" \
	"$(initrd_art OTTO_DATA="$TMP/none" INSTALL=install.sfs | grep -c .)"
expect "initrd: the art is plain ASCII, 76x20 at most" "ok" \
	"$(LC_ALL=C awk 'length($0) > 76 || /[^ -~]/ { bad = 1 } END { print (NR <= 20 && !bad) ? "ok" : "bad" }' "$ROOT/art/brand/otto-ascii.txt")"

# patch-initrd.sh keeps upstream's archive and appends ours.
mkdir -p "$TMP/ird/scripts"
cp "$FIX/upstream-initrd-init" "$TMP/ird/init"
cp "$FIX/upstream-initrd-4-fstab-fallback" "$TMP/ird/scripts/4-fstab-fallback"
echo hello >"$TMP/ird/other"
# Named like upstream's, with a leading "./".
(cd "$TMP/ird" && printf '%s\n' ./init ./scripts ./scripts/4-fstab-fallback ./other |
	cpio -o -H newc --quiet | gzip -9n) >"$TMP/up.img"
"$ROOT/tools/patch-initrd.sh" "$TMP/up.img" "$TMP/new.img"
size=$(wc -c <"$TMP/up.img")
expect "initrd: upstream's archive kept byte for byte" "same" \
	"$(cmp -s <(head -c "$size" "$TMP/new.img") "$TMP/up.img" && echo same)"
expect "initrd: our archive replaces /init and the fstab script, adds the art" \
	"init|scripts/4-fstab-fallback|otto-ascii.txt" \
	"$(tail -c +$((size + 1)) "$TMP/new.img" | gzip -dc | cpio -t --quiet 2>/dev/null | paste -sd'|')"
expect "initrd: building the live fstab is quiet" "0" \
	"$(tail -c +$((size + 1)) "$TMP/new.img" | gzip -dc | cpio -i --to-stdout --quiet scripts/4-fstab-fallback 2>/dev/null | grep -c echo)"

# --- Installer (Calamares) patch ------------------------------------------------

# shellcheck source=iso/scripts/9-otto
source "$ROOT/iso/scripts/9-otto"
# Upstream's real installer files, and our folder as build.sh stages it.
inst="$TMP/install"
cp -r "$FIX/installer" "$inst"
touch "$inst/root/Desktop/HOW_TO_USE.png" "$inst/root/Desktop/readme.desktop"
mkdir -p "$inst/usr/share/backgrounds"
echo bliss >"$inst/usr/share/backgrounds/blissified_09.png"
payload="$TMP/payload"
cp -r "$ROOT/iso/otto" "$payload"
cp -r "$ROOT/iso/boot/grub/themes/otto" "$payload/installer/grub-theme"
cp "$ROOT/art/brand/otto-ascii.txt" "$payload/otto-ascii.txt"
cal="$inst/etc/calamares"
mods="$inst/usr/lib/x86_64-linux-gnu/calamares/modules"

otto_patch_installer "$inst" "$payload"
expect "installer: four steps (welcome, disk, install, done)" "welcome|partition|finished" \
	"$(awk '/^- show:/ { show = 1; next } /^[^ ]/ { show = 0 } show && /^ +- / { sub(/^ *- /, ""); print }' "$cal/settings.conf" | paste -sd'|')"
expect "installer: defaults before gen-img, our files right after it" \
	"- otto-defaults|- gen-img|- otto-payload|- gen-fstab" \
	"$(grep -B1 -A2 -- '- gen-img$' "$cal/settings.conf" | sed 's/^ *//' | paste -sd'|')"
expect "installer: our jobs are there" "yes|yes" \
	"$([ -f "$mods/otto-payload/module.desc" ] && echo yes)|$([ -f "$mods/otto-defaults/main.py" ] && echo yes)"
expect "installer: Otto's branding" "branding: otto|yes" \
	"$(grep '^branding:' "$cal/settings.conf")|$([ -f "$cal/branding/otto/show.qml" ] && echo yes)"
expect "installer: erasing the disk is preselected" "initialPartitioningChoice: erase" \
	"$(grep '^initialPartitioningChoice' "$cal/modules/partition.conf")"
expect "installer: the PC's UEFI entry is OttoTV" 'efiBootloaderId: "OttoTV"' \
	"$(grep '^efiBootloaderId' "$cal/modules/bootloader.conf")"
expect "installer: grub-mkconfig runs our entries" 'grub_bootcfg: "/etc/calamares/scripts/10_otto"|yes' \
	"$(grep '^grub_bootcfg' "$cal/modules/bootcfg.conf")|$([ -x "$cal/scripts/10_otto" ] && echo yes)"
expect "installer: 5 s menu, our name and screen modes" \
	"GRUB_TIMEOUT=5|GRUB_TIMEOUT_STYLE=menu|GRUB_GFXMODE=1366x768,1280x720,1024x768,auto|GRUB_DISTRIBUTOR='Otto TV'" \
	"$(sed -n 's/^ *print("\(GRUB_[A-Z_]*=[^"]*\)", file=grubConf)$/\1/p' "$mods/bootcfg/main.py" | head -n 4 | paste -sd'|')"
if python3 -m py_compile "$mods/bootcfg/main.py" "$mods/otto-defaults/main.py" 2>/dev/null; then
	pass "installer: patched Python still compiles"
else
	fail "installer: patched Python still compiles" "syntax error"
fi
expect "installer: only our boot menu theme" "otto" "$(ls "$inst/usr/share/grub/themes")"
expect "installer: firmware entry in Portuguese" 'LABEL="Configurações do computador (UEFI)"' \
	"$(grep '^LABEL=' "$inst/etc/grub.d/30_uefi-firmware")"
expect "installer: opens in Portuguese" 'LANG=pt_BR.UTF-8 LANGUAGE=pt_BR calamares "$@"' \
	"$(tail -n 1 "$inst/usr/bin/aaropa-calamares")"
expect "installer: starts on GRUB, no bootloader question" "2" \
	"$(grep -c 'aaropa-calamares grub"' "$inst/root/.jwmrc")"
# What shows: menu labels and icons, the desktop icon, the files on the
# desktop. (Paths like /etc/bliss/reboot.sh or the wallpaper's file name,
# now Otto's picture, don't.)
expect "installer: desktop without BlissOS" "" \
	"$(grep -i -e 'label="[^"]*bliss' -e 'blissos_logo' -e 'readme' "$inst/root/.jwmrc"
	grep -i '^\(Name\|GenericName\|Icon\)=.*bliss' "$inst/root/Desktop/calamares.desktop"
	for f in "$inst/root/Desktop"/*; do [ "${f##*/}" = calamares.desktop ] || echo "$f"; done)"
expect "installer: Otto's wallpaper over upstream's, tiled" "same|WallpaperMode=tile|tile" \
	"$(cmp -s "$inst/usr/share/backgrounds/blissified_09.png" "$ROOT/iso/otto/installer/wallpaper.png" && echo same)|$(grep '^WallpaperMode=' "$inst/root/.config/pcmanfm/default/settings.conf")|$(sed -n 's/.*<Background type="\([a-z]*\)".*/\1/p' "$inst/root/.jwmrc")"
expect "installer: QML step list (the widget one aborts in that build)" "sidebar: qml,left|yes" \
	"$(grep '^sidebar:' "$cal/branding/otto/branding.desc")|$([ -f "$cal/branding/otto/calamares-sidebar.qml" ] && echo yes)"

expect "installer: restarts through otto-restart, checked by default" \
	'restartNowMode: user-checked|restartNowCommand: "/usr/local/bin/otto-restart"|yes|yes' \
	"$(grep -e '^restartNowMode' -e '^restartNowCommand' "$cal/modules/finished.conf" | paste -sd'|')|$([ -x "$inst/usr/local/bin/otto-restart" ] && echo yes)|$(cmp -s "$inst/etc/otto/otto-ascii.txt" "$ROOT/art/brand/otto-ascii.txt" && echo yes)"

cp "$cal/settings.conf" "$TMP/settings.once"
otto_patch_installer "$inst" "$payload"
expect "installer: patching twice changes nothing" "" "$(diff "$TMP/settings.once" "$cal/settings.conf")"

# The installer in RAM: copied and bind-mounted where upstream mounts it from.
# Usage: to_ram <MemAvailable in kB> [VAR=value...] (prints the mounts)
to_ram() {
	local kb=$1 r="$TMP/ram"
	shift
	rm -rf "$r"
	mkdir -p "$r/mnt/iso"
	head -c 3000000 /dev/urandom >"$r/mnt/iso/install.sfs"
	printf 'MemAvailable:   %s kB\n' "$kb" >"$r/meminfo"
	(
		# shellcheck disable=SC2030  # meant for this subshell only
		export OTTO_MNT="$r/mnt" SRC=iso OTTO_RAMDIR="$r/ram" OTTO_MEMINFO="$r/meminfo"
		for v in "$@"; do export "${v?}"; done
		# Sourced as the initrd does, with mount mocked.
		# shellcheck disable=SC2016  # expanded by that bash
		bash -c 'log=$1/log
			mount() { echo "mount $*" >>"$log"; }
			umount() { echo "umount $*" >>"$log"; }
			source "$2"' _ "$r" "$ROOT/iso/scripts/9-otto" >/dev/null 2>&1
	)
	sed "s#$r/##g" "$r/log" 2>/dev/null | paste -sd'|'
}
expect "installer in RAM: copied, then mounted over the stick's file" \
	"mount -t tmpfs -o size=19m,mode=0755 otto-ram ram|mount --bind ram/install.sfs mnt/iso/install.sfs|same" \
	"$(to_ram 4000000 INSTALL=install.sfs)|$(cmp -s "$TMP/ram/ram/install.sfs" "$TMP/ram/mnt/iso/install.sfs" && echo same)"
expect "installer in RAM: not with under 1 GB to spare" "" "$(to_ram 1000000 INSTALL=install.sfs)"
expect "installer in RAM: not with upstream's COPYTORAM" "" "$(to_ram 4000000 INSTALL=install.sfs COPYTORAM=1)"
expect "installer in RAM: not when booting the system" "" "$(to_ram 4000000)"

# otto-restart, the installer's last step, under dash (its /bin/sh).
# A USB stick, partition sdb1, holding the installer's /cdrom.
rs="$TMP/restart"
stick="$rs/sys/devices/pci0000:00/usb1/1-1/host6/block/sdb"
mkdir -p "$stick/sdb1" "$rs/sys/dev/block" "$rs/sys/devices/virtual/block/dm-0/slaves"
echo 1 >"$stick/removable"
touch "$stick/sdb1/partition"
ln -s ../../devices/pci0000:00/usb1/1-1/host6/block/sdb/sdb1 "$rs/sys/dev/block/8:17"
# Ventoy: the ISO through device-mapper, over the same stick.
ln -s ../../devices/virtual/block/dm-0 "$rs/sys/dev/block/253:0"
ln -s ../../../../pci0000:00/usb1/1-1/host6/block/sdb/sdb1 "$rs/sys/devices/virtual/block/dm-0/slaves/sdb1"
# A disk inside the computer.
mkdir -p "$rs/sys/devices/pci0000:00/ata1/host0/block/sda/sda2"
echo 0 >"$rs/sys/devices/pci0000:00/ata1/host0/block/sda/removable"
touch "$rs/sys/devices/pci0000:00/ata1/host0/block/sda/sda2/partition"
ln -s ../../devices/pci0000:00/ata1/host0/block/sda/sda2 "$rs/sys/dev/block/8:2"
# Usage: restart_run <major:minor of /cdrom> (in the background; pid in $!)
restart_run() {
	rm -f "$rs/tty" "$rs/log"
	echo "36 25 $1 / /cdrom ro,relatime - iso9660 /dev/x ro" >"$rs/mountinfo"
	OTTO_SYS="$rs/sys" OTTO_MOUNTINFO="$rs/mountinfo" OTTO_ART="$ROOT/art/brand/otto-ascii.txt" \
		OTTO_TTY="$rs/tty" OTTO_CHVT=: OTTO_ENTER="$rs/enter" \
		OTTO_REBOOT="echo restarted >>'$rs/log'" dash "$ROOT/iso/otto/installer/otto-restart" &
}
# Usage: restart_wait <pid> (up to 5 s)
restart_wait() {
	local i
	for i in 1 2 3 4 5 6 7 8 9 10; do
		kill -0 "$1" 2>/dev/null || return 0
		sleep 0.5
	done
	kill "$1" 2>/dev/null
}
restart_state() {
	echo "$(grep -c 'Pode tirar o pen-drive' "$rs/tty" 2>/dev/null)|$(grep -c Reiniciando "$rs/tty" 2>/dev/null)|$(cat "$rs/log" 2>/dev/null)"
}

restart_run 8:17
pid=$!
sleep 1.5
expect "restart: asks for the stick and waits" "1|0|" "$(restart_state)"
mv "$stick" "$rs/gone"
restart_wait "$pid"
expect "restart: restarts once the stick is out" "1|1|restarted" "$(restart_state)"

restart_run 8:17
pid=$!
restart_wait "$pid"
expect "restart: stick already out, restarts at once" "0|1|restarted" "$(restart_state)"
mv "$rs/gone" "$stick"

restart_run 253:0
pid=$!
sleep 1.5
expect "restart: finds the stick under Ventoy's device-mapper" "1|0|" "$(restart_state)"
touch "$rs/enter"
restart_wait "$pid"
expect "restart: Enter restarts with the stick in" "1|1|restarted" "$(restart_state)"

restart_run 8:2
pid=$!
restart_wait "$pid"
expect "restart: installer from an inner disk, no question" "0|1|restarted" "$(restart_state)"
restart_run 0:45
pid=$!
restart_wait "$pid"
expect "restart: installer in RAM (COPYTORAM), no question" "0|1|restarted" "$(restart_state)"

# Our boot entries, through grub-mkconfig's helpers (mocked).
mkdir -p "$TMP/mkconfig"
cat >"$TMP/mkconfig/grub-mkconfig_lib" <<'LIB'
prepare_grub_to_access_device() { echo "search --no-floppy --fs-uuid --set=root 1234"; }
grub_quote() { cat; }
save_default_entry() { echo "savedefault"; }
grub_add_tab() { sed 's/^/\t/'; }
LIB
: >"$TMP/mkconfig/default-grub"
entries=$(sed "s#/etc/default/grub#$TMP/mkconfig/default-grub#" "$cal/scripts/10_otto" |
	pkgdatadir="$TMP/mkconfig" GRUB_DEVICE=/dev/sda1 grub_probe=true grub_tab="$(printf '\t')" bash)
expect "installed menu: Otto TV and safe video, nothing else" "Otto TV|Otto TV em modo de vídeo seguro" \
	"$(echo "$entries" | sed -n "s/^menuentry '\([^']*\)'.*/\1/p" | paste -sd'|')"
# shellcheck disable=SC2016  # a literal $MODE
expect "installed menu: safe video passes nomodeset" "1" \
	"$(echo "$entries" | grep -c -F 'androidboot.mode=$MODE nomodeset HWACCEL=0 ROOT=/dev/sda1')"

# --- Boot animation (initrd hook) -----------------------------------------------

mkdir -p "$TMP/anim/mnt/live/otto" "$TMP/anim/android/system/product/media" "$TMP/anim/mnt/live/data/vendor"
echo otto >"$TMP/anim/mnt/live/otto/bootanimation.zip"
echo first >"$TMP/anim/mnt/live/otto/bootanimation-first.zip"
touch "$TMP/anim/android/system/product/media/bootanimation.zip" \
	"$TMP/anim/android/system/product/media/bootanimation-dark.zip" \
	"$TMP/anim/mnt/live/data/vendor/post_inst_complete"
# Usage: anim_mounts [VAR=value...] (prints the bind mounts)
anim_mounts() {
	: >"$TMP/calls"
	(
		# shellcheck disable=SC2329  # mock
		mount() { echo "mount $*" >>"$TMP/calls"; }
		# The hook reads /mnt/$SRC: point it at the fake one.
		# shellcheck disable=SC2030,SC2031  # meant for this subshell only
		export SRC="../$TMP/anim/mnt/live" OTTO_ANDROID="$TMP/anim/android" OTTO_CMDLINE="$TMP/art-cmdline"
		for v in "$@"; do export "${v?}"; done
		otto_boot_animation
	)
	sed "s#/mnt/../$TMP/anim/mnt/live/otto/##; s# $TMP/anim/android/system/product/media/# #" "$TMP/calls" | paste -sd'|'
}
echo quiet >"$TMP/art-cmdline"
expect "boot animation: Otto over both of upstream's" \
	"mount --bind bootanimation.zip bootanimation.zip|mount --bind bootanimation.zip bootanimation-dark.zip" \
	"$(anim_mounts)"
rm "$TMP/anim/mnt/live/data/vendor/post_inst_complete"
expect "boot animation: the first boot's says it takes minutes" \
	"mount --bind bootanimation-first.zip bootanimation.zip|mount --bind bootanimation-first.zip bootanimation-dark.zip" \
	"$(anim_mounts)"
expect "boot animation: so does the live system's, every time" \
	"mount --bind bootanimation-first.zip bootanimation.zip|mount --bind bootanimation-first.zip bootanimation-dark.zip" \
	"$(echo androidboot.live=true >"$TMP/art-cmdline"; anim_mounts OTTO_DATA="$TMP/none")"
expect "boot animation: stored zip with Android's desc.txt" "640 640 24|p 0 0 part0 #161B22|36" \
	"$(unzip -p "$ROOT/iso/otto/bootanimation.zip" desc.txt | paste -sd'|')|$(unzip -l "$ROOT/iso/otto/bootanimation.zip" | grep -c 'part0/[0-9]*\.png')"
expect "boot animation: frames are stored, not deflated" "0" \
	"$(unzip -v "$ROOT/iso/otto/bootanimation.zip" | grep -c Defl)"

# --- init.sh and zram fixes (initrd hook) ------------------------------------------

hooked=$(otto_hook_bootcomplete <"$FIX/upstream-init.sh")
expect "bootcomplete: our call is the first line of upstream's do_bootcomplete" \
	"function do_bootcomplete()|{|	otto_bootcomplete_early|	hciconfig | grep -q hci || pm disable com.android.bluetooth" \
	"$(echo "$hooked" | grep -A3 '^function do_bootcomplete' | paste -sd'|')"
expect "bootcomplete: nothing else in init.sh changes" "1" \
	"$(diff <(echo "$hooked") "$FIX/upstream-init.sh" | grep -c '^<')"
expect "bootcomplete: an unknown init.sh is left as is" "" \
	"$(printf 'do_other()\n{\n\ttrue\n}\n' | otto_hook_bootcomplete | diff - <(printf 'do_other()\n{\n\ttrue\n}\n'))"

# The real upstream script against the Acer's real command line, run by
# mksh like on Android (bash takes "X=(...)" for an array and hides the bug).
# DISABLE_ZRAM=1 stops it after the command-line loop, where it used to die.
if command -v mksh >/dev/null; then
	echo 'stack_depot_disable=on loop.max_part=8 BOOT_IMAGE=(hd96)/kernel root=/dev/ram0 SETUPWIZARD=0' >"$TMP/cmdline"
	run_zram() { sed "s#/proc/cmdline#$TMP/cmdline#" | DISABLE_ZRAM=1 mksh 2>&1 | grep -v 'inaccessible or not found'; }
	expect "zram: fixture reproduces upstream's bug" "E: mksh: syntax error: unexpected '/kernel'" \
		"$(run_zram <"$FIX/upstream-init.zram.sh")"
	expect "zram: fixed script gets past GRUB's BOOT_IMAGE=(hd96)/kernel" "" \
		"$(otto_fix_zram_script <"$FIX/upstream-init.zram.sh" | run_zram)"
	expect "zram: fixed script still reads our options" "SETUPWIZARD=0" \
		"$(otto_fix_zram_script <"$FIX/upstream-init.zram.sh" |
			sed "s#/proc/cmdline#$TMP/cmdline#; s#^if \[ \"\$DISABLE_ZRAM\".*#echo SETUPWIZARD=\$SETUPWIZARD; exit 0#" |
			mksh 2>/dev/null | tail -n 1)"
else
	echo "skip zram: upstream script under Android's shell (needs mksh)"
fi
mkdir -p "$TMP/android/system/vendor/etc"
cp "$FIX/upstream-init.zram.sh" "$TMP/android/system/vendor/etc/init.zram.sh"
# shellcheck disable=SC2329  # mock, called by otto_fix_zram
mount() { echo "mount $*" >>"$TMP/calls"; }
: >"$TMP/calls"
OTTO_ANDROID="$TMP/android" OTTO_TMP="$TMP" otto_fix_zram >/dev/null
expect "zram: patched copy bound over upstream's" \
	"mount --bind $TMP/otto-init.zram.sh $TMP/android/system/vendor/etc/init.zram.sh" "$(cat "$TMP/calls")"
: >"$TMP/calls"
otto_fix_zram_script <"$FIX/upstream-init.zram.sh" >"$TMP/android/system/vendor/etc/init.zram.sh"
OTTO_ANDROID="$TMP/android" OTTO_TMP="$TMP" otto_fix_zram >/dev/null
expect "zram: an already fixed script is left alone" "" "$(cat "$TMP/calls")"

# Our app as a system app.
mkdir -p "$TMP/android/system/app" "$TMP/media/otto/system_app/OttoConnect"
: >"$TMP/calls"
SRC=../$TMP/media OTTO_ANDROID="$TMP/android" otto_system_apps >/dev/null
expect "apps: our app is layered over /system/app" \
	"mount -t overlay -o lowerdir=/mnt/../$TMP/media/otto/system_app:$TMP/android/system/app overlay $TMP/android/system/app" \
	"$(cat "$TMP/calls")"
# Upstream's home screen in the same folder: hidden in the same overlay.
mkdir -p "$TMP/android/system/app/TvLauncher" "$TMP/android/system/app/Calculator"
# shellcheck disable=SC2329  # mock, called by otto_system_apps
mknod() { echo "mknod $1 $2" >>"$TMP/calls"; }
: >"$TMP/calls"
SRC=../$TMP/media OTTO_ANDROID="$TMP/android" OTTO_TMP="$TMP" otto_system_apps >/dev/null
expect "apps: upstream's home screen in /system/app hidden by the same overlay" \
	"mknod $TMP/otto-system_app-whiteouts/TvLauncher c|mount -t overlay -o lowerdir=$TMP/otto-system_app-whiteouts:/mnt/../$TMP/media/otto/system_app:$TMP/android/system/app overlay $TMP/android/system/app" \
	"$(paste -sd'|' "$TMP/calls")"
unset -f mknod
: >"$TMP/calls"
SRC=../$TMP/media OTTO_ANDROID="$TMP/android" OTTO_TMP="$TMP" OTTO_LAUNCHER=upstream otto_system_apps >/dev/null
expect "apps: OTTO_LAUNCHER=upstream keeps it" \
	"mount -t overlay -o lowerdir=/mnt/../$TMP/media/otto/system_app:$TMP/android/system/app overlay $TMP/android/system/app" \
	"$(cat "$TMP/calls")"
rm -rf "$TMP/android/system/app/TvLauncher" "$TMP/android/system/app/Calculator"
unset -f mount


# --- Updating an installed system over adb (tools/device/update.sh) -----------------

part="$TMP/part"                          # the install partition, as mounted
mkdir -p "$TMP/upd/bin" "$part/android-x/scripts" "$part/android-x/otto/user_app" \
	"$part/android-x/otto/remote" "$TMP/upd/new/scripts" "$TMP/upd/new/otto/remote"
echo old >"$part/android-x/scripts/9-otto"
echo theirs >"$part/android-x/scripts/5-other"
echo old >"$part/android-x/otto/init-hooks.sh"
echo app >"$part/android-x/otto/user_app/org.fdroid.fdroid.apk"
echo new >"$TMP/upd/new/scripts/9-otto"
echo new >"$TMP/upd/new/otto/init-hooks.sh"
echo old-initrd >"$part/android-x/initrd_a.img"
echo new-initrd >"$TMP/upd/new/initrd.img"
tar -C "$TMP/upd/new" -cf "$TMP/upd/update.tar" scripts otto initrd.img
printf '#!/bin/sh\necho 0\n' >"$TMP/upd/bin/id"
printf '#!/bin/sh\necho "/dev/block/sda1: UUID=\\"abcd-1234\\" TYPE=\\"ext4\\""\n' >"$TMP/upd/bin/blkid"
# "mounting" points the mount folder at the fake partition
# shellcheck disable=SC2016  # expanded by the stub, not here
printf '#!/bin/sh\nfor last; do :; done\nrmdir "$last" && ln -s "%s" "$last"\n' "$part" >"$TMP/upd/bin/mount"
printf '#!/bin/sh\n:\n' >"$TMP/upd/bin/umount"
chmod +x "$TMP/upd/bin"/*
touch "$TMP/upd/sda1"
echo "BOOT_IMAGE=/android-x/kernel_a quiet ROOT=UUID=abcd-1234 SRC=/android-x androidboot.slot_suffix=_a" >"$TMP/upd/cmdline"
sed "s#/dev/\*) dev=\$root ;;#/dev/*) dev=\$root ;;#; s#\[ -e \"\$dev\" \]#[ -n \"\$dev\" ]#" \
	"$ROOT/tools/device/update.sh" >"$TMP/upd/update.sh"
out=$(PATH="$TMP/upd/bin:$PATH" OTTO_CMDLINE="$TMP/upd/cmdline" OTTO_MNT="$TMP/upd/mnt" \
	sh "$TMP/upd/update.sh" "$TMP/upd/update.tar" 2>&1)
expect "update: installed files replaced, other hooks and installed apps kept" \
	"new|theirs|new|app|old" \
	"$(cat "$part/android-x/scripts/9-otto")|$(cat "$part/android-x/scripts/5-other")|$(cat "$part/android-x/otto/init-hooks.sh")|$(cat "$part/android-x/otto/user_app/org.fdroid.fdroid.apk")|$(cat "$part/android-x/otto.old/init-hooks.sh")"
expect "update: the slot's initrd replaced, the old one kept" "new-initrd|old-initrd" \
	"$(cat "$part/android-x/initrd_a.img")|$(cat "$part/android-x/initrd_a.img.old")"
expect "update: says what it did" "Otto TV atualizado: ? -> ? (/dev/block/sda1/android-x)" \
	"$(echo "$out" | head -n 1)"
echo "androidboot.live=true ROOT=LABEL=x" >"$TMP/upd/cmdline"
out=$(PATH="$TMP/upd/bin:$PATH" OTTO_CMDLINE="$TMP/upd/cmdline" OTTO_MNT="$TMP/upd/mnt2" \
	sh "$TMP/upd/update.sh" "$TMP/upd/update.tar" 2>&1)
expect "update: refuses to run on a live system" \
	"erro: isto e um sistema live (pendrive): nao ha nada instalado para atualizar" "$out"

# --- Keys (initrd hook) ------------------------------------------------------------

expect "keys: Esc becomes Back, everything else untouched" \
	"key 1     BACK|key 2     1|key 158   BACK|# key 1 ESCAPE in a comment|key 111   FORWARD_DEL" \
	"$(printf '%s\n' 'key 1     ESCAPE' 'key 2     1' 'key 158   BACK' \
		'# key 1 ESCAPE in a comment' 'key 111   FORWARD_DEL' | otto_esc_to_back | paste -sd'|')"
# Upstream's wizard and updater: found wherever upstream keeps them, hidden
# with whiteouts, one overlay per folder.
mkdir -p "$TMP/android/system/priv-app/LineageSetupWizard" \
	"$TMP/android/system/priv-app/Settings" \
	"$TMP/android/system/system_ext/priv-app/TvSetupWizard" \
	"$TMP/android/system/system_ext/priv-app/Updater" \
	"$TMP/android/system/system_ext/priv-app/TvSettingsTwoPanel"
# shellcheck disable=SC2329  # mocks, called by otto_hide
{
	mknod() { echo "mknod ${1##*/}" >>"$TMP/calls"; }
	mount() { echo "mount ${*: -1}" >>"$TMP/calls"; }
}
: >"$TMP/calls"
OTTO_ANDROID="$TMP/android" otto_hide_upstream_apps >/dev/null
expect "upstream apps: wizards and updater hidden, other system apps kept" \
	"mknod LineageSetupWizard|mount $TMP/android/system/priv-app|mknod TvSetupWizard|mknod Updater|mount $TMP/android/system/system_ext/priv-app" \
	"$(paste -sd'|' "$TMP/calls")"
: >"$TMP/calls"
OTTO_SETUP=wizard OTTO_ANDROID="$TMP/android" otto_hide_upstream_apps >/dev/null
expect "upstream apps: OTTO_SETUP=wizard keeps the wizard, not the updater" \
	"mknod Updater|mount $TMP/android/system/system_ext/priv-app" "$(paste -sd'|' "$TMP/calls")"
# The home screen, whatever its folder is called, in priv-app or app.
mkdir -p "$TMP/android/system/system_ext/priv-app/TvLauncher" \
	"$TMP/android/system/product/app/Launcher3QuickStep" \
	"$TMP/android/system/product/app/LatinIME"
: >"$TMP/calls"
OTTO_SETUP=wizard OTTO_ANDROID="$TMP/android" otto_hide_upstream_apps >/dev/null
expect "upstream apps: home screens hidden too" \
	"mknod TvLauncher|mknod Updater|mount $TMP/android/system/system_ext/priv-app|mknod Launcher3QuickStep|mount $TMP/android/system/product/app" \
	"$(paste -sd'|' "$TMP/calls" | sed 's/mknod Updater|mknod TvLauncher/mknod TvLauncher|mknod Updater/')"
: >"$TMP/calls"
OTTO_SETUP=wizard OTTO_LAUNCHER=upstream OTTO_ANDROID="$TMP/android" otto_hide_upstream_apps >/dev/null
expect "upstream apps: OTTO_LAUNCHER=upstream keeps the home screen" \
	"mknod Updater|mount $TMP/android/system/system_ext/priv-app" "$(paste -sd'|' "$TMP/calls")"
unset -f mknod mount

expect "keys: Esc with key flags still becomes Back" "key 1   BACK" \
	"$(echo 'key 1   ESCAPE  VIRTUAL' | otto_esc_to_back)"

# Apps compiled ahead of time, once the boot settles: our app first, then
# the ones the user installed.
# shellcheck disable=SC2329  # mocks, called by the compile job
{
	otto_detach() { bash -c "$1" "${@:2}"; }
	sleep() { :; }
	pm() { printf 'package:com.stremio.one\npackage:com.duckduckgo.mobile.android\n'; }
	cmd() {
		echo "cmd $*" >>"$TMP/calls"
		[ "$5" = com.stremio.one ] && { echo "Failure: no space"; return 1; }
		echo Success
	}
	log() { echo "log $3" >>"$TMP/calls"; }
	export -f pm cmd log sleep
}
: >"$TMP/calls"
otto_precompile
expect "compile: our app and the installed ones, failures logged" \
	"cmd package compile -m speed app.ottotv.connect|log compile: app.ottotv.connect|cmd package compile -m speed com.stremio.one|log compile: com.stremio.one: Failure: no space|cmd package compile -m speed com.duckduckgo.mobile.android|log compile: com.duckduckgo.mobile.android" \
	"$(paste -sd'|' "$TMP/calls")"
: >"$TMP/calls"
OTTO_COMPILE=off otto_precompile
expect "compile: OTTO_COMPILE=off" "" "$(cat "$TMP/calls")"
unset -f otto_detach sleep pm cmd log
# shellcheck source=iso/otto/init-hooks.sh
source "$ROOT/iso/otto/init-hooks.sh"

# --- Boot prefetch (initrd hook) ---------------------------------------------------

# 9-otto again: init-hooks.sh has an otto_first_boot of its own, for Android.
# shellcheck source=iso/scripts/9-otto
source "$ROOT/iso/scripts/9-otto"

pf="$TMP/pf"
mkdir -p "$pf/otto/remote" "$pf/data/vendor" "$TMP/sysfs/block/loop0/loop" "$TMP/sysfs/block/loop0/queue" \
	"$TMP/sysfs/block/sda/queue" "$TMP/pftmp"
touch "$pf/data/vendor/post_inst_complete" "$pf/system_a.img"
echo "$pf/system_a.img" >"$TMP/sysfs/block/loop0/loop/backing_file"
for q in sda loop0; do echo 128 >"$TMP/sysfs/block/$q/queue/read_ahead_kb"; done
cat >"$pf/otto/remote/otto-remote" <<'EOF'
#!/bin/sh
echo "$@" >"$(dirname "$0")/prefetch-args"
EOF
prefetch() {
	rm -f "$TMP/pftmp/prefetch-args"
	SRC=../$pf OTTO_DATA="$pf/data" OTTO_SYSFS="$TMP/sysfs" OTTO_TMP="$TMP/pftmp" otto_prefetch
	wait
}
prefetch
expect "prefetch: started with the system image and /data, read-ahead up for the boot" \
	"prefetch -list /mnt/../$pf/otto/prefetch.list $pf/system_a.img /mnt/../$pf/data.img /mnt/../$pf/data/dalvik-cache /mnt/../$pf/data/misc/apexdata /mnt/../$pf/data/app /mnt/../$pf/data/system /mnt/../$pf/data/system_de /mnt/../$pf/data/user_de|2048|2048" \
	"$(cat "$TMP/pftmp/prefetch-args" 2>/dev/null)|$(cat "$TMP/sysfs/block/sda/queue/read_ahead_kb")|$(cat "$TMP/sysfs/block/loop0/queue/read_ahead_kb")"
rm "$pf/data/vendor/post_inst_complete"
prefetch
expect "prefetch: not on a first boot" "" "$(cat "$TMP/pftmp/prefetch-args" 2>/dev/null)"
touch "$pf/data/vendor/post_inst_complete"
OTTO_PREFETCH=off prefetch
expect "prefetch: OTTO_PREFETCH=off" "" "$(cat "$TMP/pftmp/prefetch-args" 2>/dev/null)"
echo "$pf/nothing.img" >"$TMP/sysfs/block/loop0/loop/backing_file"
prefetch
expect "prefetch: no system image, nothing to do" "" "$(cat "$TMP/pftmp/prefetch-args" 2>/dev/null)"
# Boot complete puts read-ahead back, only where the initrd raised it.
echo 2048 >"$TMP/sysfs/block/sda/queue/read_ahead_kb"
echo 256 >"$TMP/sysfs/block/loop0/queue/read_ahead_kb"
OTTO_SYSFS="$TMP/sysfs" otto_restore_readahead
expect "prefetch: read-ahead back to 512 KB after the boot" "512|256" \
	"$(cat "$TMP/sysfs/block/sda/queue/read_ahead_kb")|$(cat "$TMP/sysfs/block/loop0/queue/read_ahead_kb")"

# --- Python parts -----------------------------------------------------------------

if PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover -s "$ROOT/tests" -p 'test_*.py' >"$TMP/py.log" 2>&1; then
	pass "python: F-Droid helper and installer job ($(grep -o 'Ran [0-9]* tests' "$TMP/py.log"))"
else
	fail "python: F-Droid helper and installer job" "$(tail -n 20 "$TMP/py.log")"
fi

# --- TV app (Java) -------------------------------------------------------------------

sdk_jar=$(find "${ANDROID_HOME:-/nonexistent}/platforms" -name android.jar 2>/dev/null | sort -V | tail -n 1)
if command -v javac >/dev/null && [ -n "$sdk_jar" ]; then
	mkdir -p "$TMP/java"
	if javac -nowarn -source 8 -target 8 -bootclasspath "$sdk_jar" -d "$TMP/java" \
		"$ROOT/tvapp/src/app/ottotv/connect/EnterAction.java" \
		"$ROOT/tvapp/test/EnterActionTest.java" >"$TMP/java.log" 2>&1 &&
		java -cp "$TMP/java" app.ottotv.connect.EnterActionTest >>"$TMP/java.log" 2>&1; then
		pass "tvapp: Enter (big or keypad) runs the field's action, OK stays OK"
	else
		fail "tvapp: Enter key" "$(grep -v 'warning: \[options\]' "$TMP/java.log" | tail -n 20)"
	fi
	mkdir -p "$TMP/java-home"
	home="$ROOT/tvapp/src/app/ottotv/connect/home"
	if javac -nowarn -source 8 -target 8 -bootclasspath "$sdk_jar" -d "$TMP/java-home" \
		"$home/Svg.java" "$home/Tone.java" "$home/Icons.java" \
		"$ROOT/tvapp/test/HomeMathTest.java" >"$TMP/java-home.log" 2>&1 &&
		java -cp "$TMP/java-home" app.ottotv.connect.home.HomeMathTest >>"$TMP/java-home.log" 2>&1; then
		pass "tvapp: home screen icons (SVG paths) and app colors"
	else
		fail "tvapp: home screen math" "$(grep -v 'warning: \[options\]' "$TMP/java-home.log" | tail -n 20)"
	fi
else
	echo "skip tvapp: Enter key and home screen math (need javac and ANDROID_HOME)"
fi

# --- Phone remote (Go) ---------------------------------------------------------------

if command -v go >/dev/null; then
	if (cd "$ROOT/remote" && go vet ./... && go test ./...) >"$TMP/go.log" 2>&1; then
		pass "go: phone remote ($(grep -c '^ok' "$TMP/go.log") packages)"
	else
		fail "go: phone remote" "$(tail -n 20 "$TMP/go.log")"
	fi
else
	echo "skip go: phone remote (Go not installed)"
fi

echo
if [ "$failures" -eq 0 ]; then
	echo "All tests passed."
else
	echo "$failures test(s) failed."
	exit 1
fi

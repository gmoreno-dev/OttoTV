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

# shellcheck source=iso/tvbox/init-hooks.sh
source "$ROOT/iso/tvbox/init-hooks.sh"
export TVBOX_KMSG=/dev/null # keep test logs out of this machine's kernel log
# tvbox_detach moves its child to the root cgroup: never this machine's.
export TVBOX_CGROUP_PROCS="$TMP/cgroup.procs"

# --- HDMI detection ---------------------------------------------------------

expect "hdmi: real Acer dump, TV on pcm 3" "pcmC0D3p" \
	"$(tvbox_parse_hdmi_jacks 0 <"$FIX/acer-hm77-hdmi-on.mixer.txt")"

expect "hdmi: same dump with the TV unplugged" "" \
	"$(sed "/HDMI\/DP,pcm=3 Jack/,/: values=/ s/values=on/values=off/" \
		"$FIX/acer-hm77-hdmi-on.mixer.txt" | tvbox_parse_hdmi_jacks 0)"

expect "hdmi: picks the connected port, not the first one" "pcmC1D7p" \
	"$(tvbox_parse_hdmi_jacks 1 <<'EOF'
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
	"$(tvbox_parse_hdmi_jacks 0 <<'EOF'
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
# shellcheck disable=SC2329  # called by tvbox_find_hdmi_pcm
alsa_amixer() { # mock: alsa_amixer -c <card> contents
	if [ "$2" = 1 ]; then
		printf "%s\n" "numid=9,iface=CARD,name='HDMI/DP,pcm=3 Jack'" \
			"  ; type=BOOLEAN,access=r-------,values=1" "  : values=on"
	fi
}
expect "hdmi: finds the TV on the second sound card" "pcmC1D3p" \
	"$(TVBOX_ASOUND_CARDS="$TMP/cards" tvbox_find_hdmi_pcm)"

# --- post_init end to end, with Android's commands mocked ---------------------

# shellcheck disable=SC2329  # mocks, called by the hooks under test
{
	setprop() { echo "setprop $1 $2" >>"$TMP/props"; }
	getprop() { :; }
	set_prop_if_empty() { setprop "$1" "$2"; }
	log() { :; }
	setsid() { :; } # tvbox_mark_setup_done's background job
}
: >"$TMP/props"
TVBOX_ASOUND_CARDS="$TMP/cards" post_init
expect "post_init: routes audio, sets the language and mirrors to the TV unasked" \
	"setprop persist.sys.locale pt-BR|setprop persist.sys.display.enable_on_connect.external true|setprop hal.audio.primary.hdmi 1|setprop hal.audio.out.hdmi pcmC1D3p" \
	"$(paste -sd'|' "$TMP/props")"

: >"$TMP/props"
TVBOX_AUDIO=off TVBOX_ASOUND_CARDS="$TMP/cards" post_init
expect "post_init: TVBOX_AUDIO=off leaves audio alone" \
	"setprop persist.sys.locale pt-BR|setprop persist.sys.display.enable_on_connect.external true" \
	"$(paste -sd'|' "$TMP/props")"

: >"$TMP/props"
TVBOX_MIRROR=ask tvbox_setup_mirror
expect "mirror: TVBOX_MIRROR=ask brings back Android's question" \
	"setprop persist.sys.display.enable_on_connect.external false" "$(cat "$TMP/props")"
unset -f alsa_amixer setprop getprop set_prop_if_empty log setsid

# --- On-screen keyboard ---------------------------------------------------------

# Device list modeled on the Acer: device names and key sets from its
# `getevent -p`, bitmaps in the kernel's /proc/bus/input/devices format.
expect "keyboards: laptop keyboard found, hotkeys and touchpad ignored" \
	"AT Translated Set 2 keyboard" \
	"$(tvbox_parse_keyboards <"$FIX/input-devices-laptop.txt")"

cat "$FIX/input-devices-laptop.txt" - >"$TMP/devices" <<'EOF'
I: Bus=0003 Vendor=046d Product=c31c Version=0110
N: Name="Logitech USB Keyboard"
H: Handlers=sysrq kbd event14 leds
B: EV=120013
B: KEY=1000000000007 ff9f207ac14057ff febeffdfffefffff fffffffffffffffe

I: Bus=0006 Vendor=0000 Product=0000 Version=0001
N: Name="TV Box Remote Keyboard"
H: Handlers=sysrq kbd event15
B: EV=3
B: KEY=1000000000007 ff9f207ac14057ff febeffdfffefffff fffffffffffffffe

EOF
expect "keyboards: USB keyboard found, our virtual remote skipped" \
	"AT Translated Set 2 keyboard|Logitech USB Keyboard" \
	"$(tvbox_parse_keyboards <"$TMP/devices" | paste -sd'|')"

grep -v -e '^B: KEY=402000000' "$FIX/input-devices-laptop.txt" >"$TMP/no-keyboard"
expect "keyboards: none on a remote-only setup" "" \
	"$(tvbox_parse_keyboards <"$TMP/no-keyboard")"

# shellcheck disable=SC2329  # mocks, called by tvbox_setup_ime
{
	ime() {
		echo "ime $*" >>"$TMP/calls"
		[ "$1" = list ] && echo "$TVBOX_HARD_IME"
		return 0
	}
	settings() { echo "settings $*" >>"$TMP/calls"; }
	log() { :; }
}
: >"$TMP/calls"
TVBOX_INPUT_DEVICES="$FIX/input-devices-laptop.txt" tvbox_setup_ime
expect "ime: physical keyboard switches to our keyboard" \
	"ime list -a -s|ime enable $TVBOX_HARD_IME|ime set $TVBOX_HARD_IME|settings put secure show_ime_with_hard_keyboard 0" \
	"$(paste -sd'|' "$TMP/calls")"

: >"$TMP/calls"
TVBOX_INPUT_DEVICES="$TMP/no-keyboard" tvbox_setup_ime
expect "ime: no physical keyboard keeps the TV keyboard" "" "$(cat "$TMP/calls")"

: >"$TMP/calls"
TVBOX_IME=tv TVBOX_INPUT_DEVICES="$FIX/input-devices-laptop.txt" tvbox_setup_ime
expect "ime: TVBOX_IME=tv keeps the TV keyboard" "" "$(cat "$TMP/calls")"
unset -f ime settings log

# --- Phone remote start-up (Android-side hook) ---------------------------------------

mkdir -p "$TMP/tvbox/remote" "$TMP/state"
echo 'fake binary' >"$TMP/tvbox/remote/tvbox-remote"
# shellcheck disable=SC2329  # mocks, called by tvbox_start_remote
{
	setsid() { echo "setsid $1 $2 ... $4 $5" >>"$TMP/calls"; }
	log() { :; }
}
: >"$TMP/calls"
TVBOX_STATE="$TMP/state" TVBOX_DIR="$TMP/tvbox" tvbox_start_remote
wait # the hook backgrounds setsid
expect "remote: copied out of /boot and started under setsid" \
	"fake binary|setsid sh -c ... $TMP/state/tvbox-remote $TMP/state" \
	"$(cat "$TMP/state/tvbox-remote")|$(cat "$TMP/calls")"
: >"$TMP/calls"
if TVBOX_STATE="$TMP/state" TVBOX_DIR="$TMP/missing" tvbox_start_remote; then
	fail "remote: missing binary is reported" "tvbox_start_remote succeeded"
else
	pass "remote: missing binary is reported"
fi
unset -f setsid log

# --- Background jobs and first-run setup -------------------------------------------

# init kills init.sh's cgroup as soon as init.sh exits, so the child must be
# out of it before tvbox_detach returns, not whenever the child gets to it.
# shellcheck disable=SC2329  # mock, called by tvbox_detach
setsid() { sleep 0.3; echo "$BASHPID" >"$TMP/child"; }
rm -f "$TMP/child" "$TMP/cgroup.procs"
tvbox_detach 'true'
moved=$(cat "$TMP/cgroup.procs" 2>/dev/null)
started=$([ -e "$TMP/child" ] && echo yes || echo no)
wait
expect "detach: child leaves init.sh's cgroup before we return" \
	"no|$(cat "$TMP/child")" "$started|$moved"
unset -f setsid

# shellcheck disable=SC2329  # mocks, called by tvbox_setup_done
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
SETTINGS_VALUE=0 tvbox_setup_done
expect "setup: marked as done at boot complete" \
	"put device_provisioned 1|put user_setup_complete 1|put tv_user_setup_complete 1" \
	"$(paste -sd'|' "$TMP/calls")"
: >"$TMP/calls"
SETTINGS_VALUE=1 tvbox_setup_done
expect "setup: left alone when already done" "" "$(cat "$TMP/calls")"
: >"$TMP/calls"
SETTINGS_VALUE=0 TVBOX_SETUP=wizard tvbox_setup_done
expect "setup: TVBOX_SETUP=wizard leaves it to the wizard" "" "$(cat "$TMP/calls")"
unset -f settings log

# Boot complete: the early call does the work once; post_bootcomplete only
# steps in when upstream's init.sh changed and the call wasn't added.
# shellcheck disable=SC2329  # mocks, called by the hooks under test
{
	tvbox_setup_mirror() { echo mirror >>"$TMP/calls"; }
	tvbox_setup_done() { echo setup >>"$TMP/calls"; }
	tvbox_setup_ime() { echo ime >>"$TMP/calls"; }
	tvbox_start_remote() { echo remote >>"$TMP/calls"; [ -z "${REMOTE_FAILS:-}" ]; }
	tvbox_wait_for_apps() { echo wait >>"$TMP/calls"; }
	tvbox_offer_pairing() { echo pairing >>"$TMP/calls"; }
	getprop() { echo pcmC0D3p; }
	log() { :; }
}
: >"$TMP/calls"
(tvbox_bootcomplete_early && post_bootcomplete)
expect "bootcomplete: early call does the work, post_bootcomplete doesn't repeat it" \
	"mirror|setup|remote|wait|ime|pairing" "$(paste -sd'|' "$TMP/calls")"
: >"$TMP/calls"
(post_bootcomplete)
expect "bootcomplete: post_bootcomplete steps in without the early call" \
	"mirror|setup|remote|wait|ime|pairing" "$(paste -sd'|' "$TMP/calls")"
: >"$TMP/calls"
(REMOTE_FAILS=1 tvbox_bootcomplete_early)
expect "bootcomplete: no pairing screen when the remote didn't start" \
	"mirror|setup|remote|wait|ime" "$(paste -sd'|' "$TMP/calls")"
unset -f tvbox_setup_mirror tvbox_setup_ime tvbox_start_remote tvbox_wait_for_apps tvbox_offer_pairing getprop log
# shellcheck source=iso/tvbox/init-hooks.sh
source "$ROOT/iso/tvbox/init-hooks.sh"

# Installed systems unlock the user a few seconds after boot completes, and
# our app is invisible until then.
# shellcheck disable=SC2329  # mocks, called by tvbox_wait_for_apps
{
	ime() {
		echo x >>"$TMP/ime-calls"
		[ "$(wc -l <"$TMP/ime-calls")" -gt "$LOCKED_FOR" ] && echo "$TVBOX_HARD_IME"
		echo com.android.inputmethod.leanback/.service.LeanbackImeService
	}
	sleep() { :; }
	log() { echo "$3" >>"$TMP/calls"; }
}
: >"$TMP/calls"
: >"$TMP/ime-calls"
LOCKED_FOR=0 tvbox_wait_for_apps
expect "apps: live system, no wait" "1|" "$(wc -l <"$TMP/ime-calls")|$(cat "$TMP/calls")"
: >"$TMP/ime-calls"
LOCKED_FOR=4 tvbox_wait_for_apps
expect "apps: installed system waits for the unlock" "apps: visible after 4s (user unlocked)" "$(cat "$TMP/calls")"
: >"$TMP/calls"
: >"$TMP/ime-calls"
if LOCKED_FOR=999 TVBOX_UNLOCK_WAIT=3 tvbox_wait_for_apps; then
	fail "apps: gives up eventually" "returned success"
else
	expect "apps: gives up eventually" "apps: ours still not visible after 3s" "$(cat "$TMP/calls")"
fi
unset -f ime sleep log
# Restore the real one for later tests.
# shellcheck source=iso/tvbox/init-hooks.sh
source "$ROOT/iso/tvbox/init-hooks.sh"

# --- GRUB menu ----------------------------------------------------------------

cp "$FIX/upstream-functions.cfg" "$TMP/functions.cfg"
if "$ROOT/tools/patch-functions-cfg.sh" "$TMP/functions.cfg" 2>/dev/null; then
	pass "grub: functions.cfg patch applies to upstream"
else
	fail "grub: functions.cfg patch applies to upstream" "script failed"
fi
if grep -q 'hwacccel' "$TMP/functions.cfg"; then
	fail "grub: hwaccel typo fixed" "\$hwacccel still present"
else
	pass "grub: hwaccel typo fixed"
fi

if PRODUCT_NAME="TV Box" VERSION="0.1.0" ISO_LABEL="LineageOS_20260331" \
	BOOT_TIMEOUT=5 EXTRA_KERNEL_ARGS="SET_TZ_LOCATION=America/Sao_Paulo" \
	"$ROOT/tools/render-grub.sh" "$ROOT/iso/boot/grub/grub.cfg.in" "$TMP/grub.cfg" 2>/dev/null; then
	pass "grub: template renders"
else
	fail "grub: template renders" "render-grub.sh failed"
fi
expect "grub: boot media label kept" "1" \
	"$(grep -c 'ROOT=LABEL=LineageOS_20260331 ' "$TMP/grub.cfg")"

# --- Installer (Calamares) patch ------------------------------------------------

# shellcheck source=iso/scripts/9-tvbox
source "$ROOT/iso/scripts/9-tvbox"
inst="$TMP/install"
mkdir -p "$inst/etc/calamares"
cp "$FIX/upstream-calamares-settings.conf" "$inst/etc/calamares/settings.conf"

tvbox_patch_installer "$inst" "$ROOT/iso/tvbox" >/dev/null
expect "installer: our job runs right after gen-img" \
	"- gen-img|- tvbox-payload|- gen-fstab" \
	"$(grep -A2 -- '- gen-img$' "$inst/etc/calamares/settings.conf" | sed 's/^ *//' | paste -sd'|')"
expect "installer: job module copied" "yes" \
	"$([ -f "$inst/usr/lib/x86_64-linux-gnu/calamares/modules/tvbox-payload/module.desc" ] && echo yes)"

tvbox_patch_installer "$inst" "$ROOT/iso/tvbox" >/dev/null
expect "installer: patching twice adds the step once" "1" \
	"$(grep -c -- '- tvbox-payload$' "$inst/etc/calamares/settings.conf")"

# --- init.sh and zram fixes (initrd hook) ------------------------------------------

hooked=$(tvbox_hook_bootcomplete <"$FIX/upstream-init.sh")
expect "bootcomplete: our call is the first line of upstream's do_bootcomplete" \
	"function do_bootcomplete()|{|	tvbox_bootcomplete_early|	hciconfig | grep -q hci || pm disable com.android.bluetooth" \
	"$(echo "$hooked" | grep -A3 '^function do_bootcomplete' | paste -sd'|')"
expect "bootcomplete: nothing else in init.sh changes" "1" \
	"$(diff <(echo "$hooked") "$FIX/upstream-init.sh" | grep -c '^<')"
expect "bootcomplete: an unknown init.sh is left as is" "" \
	"$(printf 'do_other()\n{\n\ttrue\n}\n' | tvbox_hook_bootcomplete | diff - <(printf 'do_other()\n{\n\ttrue\n}\n'))"

# The real upstream script against the Acer's real command line, run by
# mksh like on Android (bash takes "X=(...)" for an array and hides the bug).
# DISABLE_ZRAM=1 stops it after the command-line loop, where it used to die.
if command -v mksh >/dev/null; then
	echo 'stack_depot_disable=on loop.max_part=8 BOOT_IMAGE=(hd96)/kernel root=/dev/ram0 SETUPWIZARD=0' >"$TMP/cmdline"
	run_zram() { sed "s#/proc/cmdline#$TMP/cmdline#" | DISABLE_ZRAM=1 mksh 2>&1 | grep -v 'inaccessible or not found'; }
	expect "zram: fixture reproduces upstream's bug" "E: mksh: syntax error: unexpected '/kernel'" \
		"$(run_zram <"$FIX/upstream-init.zram.sh")"
	expect "zram: fixed script gets past GRUB's BOOT_IMAGE=(hd96)/kernel" "" \
		"$(tvbox_fix_zram_script <"$FIX/upstream-init.zram.sh" | run_zram)"
	expect "zram: fixed script still reads our options" "SETUPWIZARD=0" \
		"$(tvbox_fix_zram_script <"$FIX/upstream-init.zram.sh" |
			sed "s#/proc/cmdline#$TMP/cmdline#; s#^if \[ \"\$DISABLE_ZRAM\".*#echo SETUPWIZARD=\$SETUPWIZARD; exit 0#" |
			mksh 2>/dev/null | tail -n 1)"
else
	echo "skip zram: upstream script under Android's shell (needs mksh)"
fi
mkdir -p "$TMP/android/system/vendor/etc"
cp "$FIX/upstream-init.zram.sh" "$TMP/android/system/vendor/etc/init.zram.sh"
# shellcheck disable=SC2329  # mock, called by tvbox_fix_zram
mount() { echo "mount $*" >>"$TMP/calls"; }
: >"$TMP/calls"
TVBOX_ANDROID="$TMP/android" TVBOX_TMP="$TMP" tvbox_fix_zram >/dev/null
expect "zram: patched copy bound over upstream's" \
	"mount --bind $TMP/tvbox-init.zram.sh $TMP/android/system/vendor/etc/init.zram.sh" "$(cat "$TMP/calls")"
: >"$TMP/calls"
tvbox_fix_zram_script <"$FIX/upstream-init.zram.sh" >"$TMP/android/system/vendor/etc/init.zram.sh"
TVBOX_ANDROID="$TMP/android" TVBOX_TMP="$TMP" tvbox_fix_zram >/dev/null
expect "zram: an already fixed script is left alone" "" "$(cat "$TMP/calls")"

# Our app as a system app.
mkdir -p "$TMP/android/system/app" "$TMP/media/tvbox/system_app/TVBoxConnect"
: >"$TMP/calls"
SRC=../$TMP/media TVBOX_ANDROID="$TMP/android" tvbox_system_apps >/dev/null
expect "apps: our app is layered over /system/app" \
	"mount -t overlay -o lowerdir=/mnt/../$TMP/media/tvbox/system_app:$TMP/android/system/app overlay $TMP/android/system/app" \
	"$(cat "$TMP/calls")"
unset -f mount


# --- Updating an installed system over adb (tools/device/update.sh) -----------------

part="$TMP/part"                          # the install partition, as mounted
mkdir -p "$TMP/upd/bin" "$part/android-x/scripts" "$part/android-x/tvbox/user_app" \
	"$part/android-x/tvbox/remote" "$TMP/upd/new/scripts" "$TMP/upd/new/tvbox/remote"
echo old >"$part/android-x/scripts/9-tvbox"
echo theirs >"$part/android-x/scripts/5-other"
echo old >"$part/android-x/tvbox/init-hooks.sh"
echo app >"$part/android-x/tvbox/user_app/org.fdroid.fdroid.apk"
echo new >"$TMP/upd/new/scripts/9-tvbox"
echo new >"$TMP/upd/new/tvbox/init-hooks.sh"
tar -C "$TMP/upd/new" -cf "$TMP/upd/update.tar" scripts tvbox
printf '#!/bin/sh\necho 0\n' >"$TMP/upd/bin/id"
printf '#!/bin/sh\necho "/dev/block/sda1: UUID=\\"abcd-1234\\" TYPE=\\"ext4\\""\n' >"$TMP/upd/bin/blkid"
# "mounting" points the mount folder at the fake partition
# shellcheck disable=SC2016  # expanded by the stub, not here
printf '#!/bin/sh\nfor last; do :; done\nrmdir "$last" && ln -s "%s" "$last"\n' "$part" >"$TMP/upd/bin/mount"
printf '#!/bin/sh\n:\n' >"$TMP/upd/bin/umount"
chmod +x "$TMP/upd/bin"/*
touch "$TMP/upd/sda1"
echo "BOOT_IMAGE=/android-x/kernel quiet ROOT=UUID=abcd-1234 SRC=/android-x" >"$TMP/upd/cmdline"
sed "s#/dev/\*) dev=\$root ;;#/dev/*) dev=\$root ;;#; s#\[ -e \"\$dev\" \]#[ -n \"\$dev\" ]#" \
	"$ROOT/tools/device/update.sh" >"$TMP/upd/update.sh"
out=$(PATH="$TMP/upd/bin:$PATH" TVBOX_CMDLINE="$TMP/upd/cmdline" TVBOX_MNT="$TMP/upd/mnt" \
	sh "$TMP/upd/update.sh" "$TMP/upd/update.tar" 2>&1)
expect "update: installed files replaced, other hooks and installed apps kept" \
	"new|theirs|new|app|old" \
	"$(cat "$part/android-x/scripts/9-tvbox")|$(cat "$part/android-x/scripts/5-other")|$(cat "$part/android-x/tvbox/init-hooks.sh")|$(cat "$part/android-x/tvbox/user_app/org.fdroid.fdroid.apk")|$(cat "$part/android-x/tvbox.old/init-hooks.sh")"
expect "update: says what it did" "TV Box atualizado: ? -> ? (/dev/block/sda1/android-x)" \
	"$(echo "$out" | head -n 1)"
echo "androidboot.live=true ROOT=LABEL=x" >"$TMP/upd/cmdline"
out=$(PATH="$TMP/upd/bin:$PATH" TVBOX_CMDLINE="$TMP/upd/cmdline" TVBOX_MNT="$TMP/upd/mnt2" \
	sh "$TMP/upd/update.sh" "$TMP/upd/update.tar" 2>&1)
expect "update: refuses to run on a live system" \
	"erro: isto e um sistema live (pendrive): nao ha nada instalado para atualizar" "$out"

# --- Keys (initrd hook) ------------------------------------------------------------

expect "keys: Esc becomes Back, everything else untouched" \
	"key 1     BACK|key 2     1|key 158   BACK|# key 1 ESCAPE in a comment|key 111   FORWARD_DEL" \
	"$(printf '%s\n' 'key 1     ESCAPE' 'key 2     1' 'key 158   BACK' \
		'# key 1 ESCAPE in a comment' 'key 111   FORWARD_DEL' | tvbox_esc_to_back | paste -sd'|')"
# Setup wizard: found wherever upstream keeps it, hidden with whiteouts.
mkdir -p "$TMP/android/system/priv-app/LineageSetupWizard" \
	"$TMP/android/system/priv-app/Settings" \
	"$TMP/android/system/system_ext/priv-app/TvSetupWizard"
# shellcheck disable=SC2329  # mocks, called by tvbox_hide
{
	mknod() { echo "mknod ${1##*/}" >>"$TMP/calls"; }
	mount() { echo "mount ${*: -1}" >>"$TMP/calls"; }
}
: >"$TMP/calls"
TVBOX_ANDROID="$TMP/android" tvbox_hide_setup_wizard >/dev/null
expect "setup: wizards hidden, other system apps kept" \
	"mknod LineageSetupWizard|mount $TMP/android/system/priv-app|mknod TvSetupWizard|mount $TMP/android/system/system_ext/priv-app" \
	"$(paste -sd'|' "$TMP/calls")"
: >"$TMP/calls"
TVBOX_SETUP=wizard TVBOX_ANDROID="$TMP/android" tvbox_hide_setup_wizard >/dev/null
expect "setup: TVBOX_SETUP=wizard keeps upstream's wizard" "" "$(cat "$TMP/calls")"
unset -f mknod mount

expect "keys: Esc with key flags still becomes Back" "key 1   BACK" \
	"$(echo 'key 1   ESCAPE  VIRTUAL' | tvbox_esc_to_back)"

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
		"$ROOT/tvapp/src/app/tvbox/connect/EnterAction.java" \
		"$ROOT/tvapp/test/EnterActionTest.java" >"$TMP/java.log" 2>&1 &&
		java -cp "$TMP/java" app.tvbox.connect.EnterActionTest >>"$TMP/java.log" 2>&1; then
		pass "tvapp: Enter (big or keypad) runs the field's action, OK stays OK"
	else
		fail "tvapp: Enter key" "$(grep -v 'warning: \[options\]' "$TMP/java.log" | tail -n 20)"
	fi
else
	echo "skip tvapp: Enter key (needs javac and ANDROID_HOME)"
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

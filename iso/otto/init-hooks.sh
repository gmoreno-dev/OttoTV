# shellcheck shell=sh
# ---- Otto TV hooks: prepended to /system/etc/init.sh at boot ----------------
#
# Upstream init.sh calls post_init (end of `init.sh`, run on post-fs, before
# audioserver starts) and post_bootcomplete (end of `init.sh bootcomplete`)
# but never defines them, so we define them here. Before either runs,
# init.sh evaluates every uppercase VAR=value on the kernel command line,
# so options like OTTO_AUDIO=off arrive as shell variables.
#
# Only function definitions live here: init.sh runs its own code after us.

# otto-remote's folder: its copy of itself, the paired phones, the laptop
# screen's and the sound's choices.
OTTO_STATE=${OTTO_STATE:-/data/misc/otto}

otto_log() {
	# /dev/kmsg works this early (read it with `dmesg`); logcat once logd is up.
	echo "otto: $*" >"${OTTO_KMSG:-/dev/kmsg}" 2>/dev/null
	log -t otto "$*" 2>/dev/null
}

# Runs a shell script in the background, outside init.sh's process group:
# init kills a service's whole group (its cgroup) when it exits, and init.sh
# is a oneshot service. Inside the script, $0, $1... are the extra arguments.
# Usage: otto_detach '<script>' [args...]
#
# The child is moved out of the cgroup here, before we return. Up to 0.3.3
# the child moved itself, which races init: when otto_detach was the last
# thing init.sh did, init could kill the group first. That is how the
# setup-done step died silently, leaving Home and developer options dead.
otto_detach() {
	local script="$1" pid g
	shift
	setsid sh -c "$script" "$@" </dev/null >/dev/null 2>&1 &
	pid=$!
	for g in ${OTTO_CGROUP_PROCS:-/sys/fs/cgroup/cgroup.procs /acct/cgroup.procs}; do
		echo "$pid" >"$g"
	done 2>/dev/null
}

# ---- First-run setup ------------------------------------------------------------
# Our initrd hook hides upstream's setup wizard (see otto_hide_upstream_apps).
# Without a wizard nobody marks setup as done, and then Android TV ignores
# the Home key and Settings locks developer options, so we mark it at boot
# complete. (Up to 0.3.4 a background job did it earlier, from post_init;
# it never lived long enough to see the settings service.)
otto_setup_done() {
	[ "${OTTO_SETUP:-ours}" = wizard ] && return 0
	if [ "$(settings get global device_provisioned 2>/dev/null)" = 1 ] &&
		[ "$(settings get secure user_setup_complete 2>/dev/null)" = 1 ]; then
		return 0
	fi
	settings put global device_provisioned 1
	settings put secure user_setup_complete 1
	settings put secure tv_user_setup_complete 1
	# The name on the network, in Bluetooth and in Settings (the wizard would
	# have asked). Only now, on first boot: a name the user picks later stays.
	settings put global device_name "Otto TV"
	settings put secure bluetooth_name "Otto TV"
	otto_log "setup: marked as done"
}

# Reads `alsa_amixer -c <card> contents` on stdin and prints pcmC<card>D<dev>p
# for the first HDMI/DP output whose jack reports a connected display, e.g.
#   numid=25,iface=CARD,name='HDMI/DP,pcm=3 Jack'
#     ; type=BOOLEAN,access=r-------,values=1
#     : values=on
otto_parse_hdmi_jacks() {
	awk -v card="$1" '
		/name=.HDMI\/DP,pcm=[0-9]+ Jack./ {
			match($0, /pcm=[0-9]+/)
			pcm = substr($0, RSTART + 4, RLENGTH - 4)
			next
		}
		pcm != "" && /: values=/ {
			if ($0 ~ /values=on/) { printf "pcmC%sD%sp\n", card, pcm; exit }
			pcm = ""
		}
	'
}

# Prints the PCM device of the first sound card output with a TV attached.
# OTTO_ASOUND_CARDS only exists so tests can point at a fixture.
otto_find_hdmi_pcm() {
	local card pcm
	# shellcheck disable=SC2013  # sed prints one bare card number per line
	for card in $(sed -n 's/^ *\([0-9][0-9]*\) \[.*/\1/p' "${OTTO_ASOUND_CARDS:-/proc/asound/cards}" 2>/dev/null); do
		pcm=$(alsa_amixer -c "$card" contents 2>/dev/null | otto_parse_hdmi_jacks "$card")
		if [ -n "$pcm" ]; then
			echo "$pcm"
			return 0
		fi
	done
	return 1
}

# Points the x86 audio HAL at the HDMI output that has a TV on it. The HAL
# only uses its HDMI slot when hal.audio.primary.hdmi is set: with
# hal.audio.out alone it parks the HDMI device and finds no output (-19).
#
# Unless the quick panel sent the sound to the laptop: otto-remote keeps
# that choice in $OTTO_STATE/audio and switches with the same properties.
# hal.audio.primary.hdmi is then 0, never left empty: empty, the HAL goes by
# Android's own routing (property_get_bool's default), which may be HDMI.
# hal.audio.out.hdmi and otto.audio.hdmi keep the TV's output either way,
# so the panel can send the sound back to it.
OTTO_TV_SOUND_PROP=otto.audio.hdmi
otto_setup_audio() {
	local pcm=""
	if [ "${OTTO_AUDIO:-auto}" != "auto" ]; then
		otto_log "audio: OTTO_AUDIO=$OTTO_AUDIO, not touching audio"
		return 1
	fi
	if pcm=$(otto_find_hdmi_pcm); then
		setprop "$OTTO_TV_SOUND_PROP" "$pcm"
		setprop hal.audio.out.hdmi "$pcm"
	fi
	if [ "$(cat "$OTTO_STATE/audio" 2>/dev/null)" = notebook ]; then
		setprop hal.audio.primary.hdmi 0
		otto_log "audio: sound on the laptop (quick panel)${pcm:+, TV on $pcm}"
		return 1
	fi
	if [ -z "$pcm" ]; then
		otto_log "audio: no display on HDMI, keeping the default output"
		return 1
	fi
	setprop hal.audio.primary.hdmi 1
	otto_log "audio: display on HDMI, routing sound to $pcm"
}

# ---- GPU clock ------------------------------------------------------------------
# Intel GPUs start each burst of work at their lowest clock (350 MHz on the
# Acer's HD 4000), and a 200 ms animation is over before it ramps up: the
# home screen's frames took 8 ms of GPU time at 350 MHz and 5 ms at 650
# (RP1, the clock the GPU is most efficient at). So RP1 becomes the floor.
# Idle, the GPU still powers down (RC6). OTTO_GPU=off keeps the default.
otto_setup_gpu() {
	local card min rp1
	[ "${OTTO_GPU:-auto}" = auto ] || return 0
	for card in "${OTTO_SYSFS:-/sys}"/class/drm/card[0-9]; do
		rp1=$(cat "$card/gt_RP1_freq_mhz" 2>/dev/null) || continue
		min=$(cat "$card/gt_min_freq_mhz" 2>/dev/null) || continue
		[ "$rp1" -gt "$min" ] 2>/dev/null || continue
		echo "$rp1" >"$card/gt_min_freq_mhz" &&
			otto_log "gpu: ${card##*/} runs at $rp1 MHz or more (was $min)"
	done
	return 0
}

# ---- On-screen keyboard ------------------------------------------------------
# Android TV's keyboard (LeanbackIME) shows up even with a physical keyboard
# plugged in: it overrides that check on purpose, so no setting hides it.
# With a physical keyboard we switch to our own (tvapp/, KeyboardService):
# it draws nothing, and Enter does what the TV keyboard's "›" button does,
# which screens like the Wi-Fi password need to continue. Without a physical
# keyboard the TV keyboard stays, since remote-only setups need it to type.

OTTO_HARD_IME=app.ottotv.connect/.KeyboardService

# Reads /proc/bus/input/devices on stdin and prints the name of every
# keyboard with letter keys (Q to P all present), skipping our own virtual
# devices. The last word of "B: KEY=" holds key codes 0-63, as hex.
otto_parse_keyboards() {
	awk '
		function hexval(s,   i, v) {
			v = 0
			s = tolower(s)
			for (i = 1; i <= length(s); i++)
				v = v * 16 + index("0123456789abcdef", substr(s, i, 1)) - 1
			return v
		}
		/^N: Name=/ { name = substr($0, 9); gsub(/^"|"$/, "", name) }
		/^B: KEY=/ {
			n = split(substr($0, 8), w, " ")
			low = w[n]
			if (length(low) > 8) low = substr(low, length(low) - 7)
			if (int(hexval(low) / 65536) % 1024 == 1023 && name !~ /^Otto Remote/)
				print name
		}
		/^$/ { name = "" }
	'
}

otto_setup_ime() {
	local kbd
	case "${OTTO_IME:-auto}" in
	tv)
		otto_log "ime: OTTO_IME=tv, keeping the TV keyboard"
		return 0
		;;
	hard) kbd="OTTO_IME=hard" ;;
	*)
		kbd=$(otto_parse_keyboards <"${OTTO_INPUT_DEVICES:-/proc/bus/input/devices}" | head -n 1)
		if [ -z "$kbd" ]; then
			otto_log "ime: no physical keyboard, keeping the TV keyboard"
			return 0
		fi
		;;
	esac
	if ! ime list -a -s 2>/dev/null | grep -qx "$OTTO_HARD_IME"; then
		otto_log "ime: $OTTO_HARD_IME is not installed"
		return 1
	fi
	ime enable "$OTTO_HARD_IME" >/dev/null 2>&1
	ime set "$OTTO_HARD_IME" >/dev/null 2>&1
	settings put secure show_ime_with_hard_keyboard 0
	otto_log "ime: physical keyboard ($kbd), on-screen keyboard hidden"
}

# ---- Phone remote -------------------------------------------------------------
# otto-remote serves the phone remote page and types what it sends through
# virtual input devices. Our initrd hook makes our files visible at
# /boot/otto (OTTO_DIR only exists so tests can point elsewhere).
OTTO_CONNECT_APP=app.ottotv.connect/.ConnectActivity

otto_start_remote() {
	local dir bin="$OTTO_STATE/otto-remote"
	if [ "${OTTO_REMOTE:-on}" != on ]; then
		otto_log "remote: OTTO_REMOTE=$OTTO_REMOTE, not starting"
		return 1
	fi
	dir=${OTTO_DIR:-/boot/otto}
	if [ ! -f "$dir/remote/otto-remote" ]; then
		otto_log "remote: $dir/remote/otto-remote not found"
		return 1
	fi
	mkdir -p "$OTTO_STATE" && chmod 700 "$OTTO_STATE"
	# Run a copy: an install partition may be mounted noexec on /boot.
	cp "$dir/remote/otto-remote" "$bin" && chmod 700 "$bin" || return 1

	# Restarted if it ever exits.
	# shellcheck disable=SC2016  # expanded by the inner shell
	otto_detach '
		while :; do
			"$0" -state "$1" >"$1/remote.log" 2>&1
			sleep 3
		done' "$bin" "$OTTO_STATE"
	otto_log "remote: started"
}

# Until a phone is paired, open the "Connect phone" screen: it is also our
# first-run screen, asking for Wi-Fi first when the TV is offline. Live
# sessions keep nothing, so they show it on every boot.
otto_offer_pairing() {
	[ -s "$OTTO_STATE/paired" ] && return 0
	if am start -n "$OTTO_CONNECT_APP" >/dev/null 2>&1; then
		otto_log "remote: showing the pairing screen"
	else
		otto_log "remote: could not open the pairing screen"
	fi
}

# ---- Home screen ---------------------------------------------------------------
# Our launcher (tvapp/, home/HomeActivity) replaces upstream's, which our
# initrd hook hides (otto_hide_upstream_apps; OTTO_LAUNCHER=upstream keeps
# it). Two of its permissions are ones a user would grant in Settings:
# usage access, for "recently used", and showing over other apps, for the
# notices over the app on screen. As a system app, it gets them here.
OTTO_HOME_APP=app.ottotv.connect/.home.HomeActivity

otto_setup_home() {
	local app="${OTTO_HOME_APP%%/*}" home
	appops set "$app" GET_USAGE_STATS allow >/dev/null 2>&1
	appops set "$app" SYSTEM_ALERT_WINDOW allow >/dev/null 2>&1
	if [ "${OTTO_LAUNCHER:-ours}" = upstream ]; then
		otto_log "home: OTTO_LAUNCHER=upstream, keeping upstream's home screen"
		return 0
	fi
	home=$(cmd package resolve-activity --brief -a android.intent.action.MAIN \
		-c android.intent.category.HOME 2>/dev/null | tail -n 1)
	case "$home" in
	"$app"/*) return 0 ;;
	*/*)
		# A launcher the user installed and picked stays theirs.
		if pm list packages -3 2>/dev/null | grep -qx "package:${home%%/*}"; then
			otto_log "home: keeping the user's launcher, ${home%%/*}"
			return 0
		fi
		;;
	esac
	# Otherwise ours, without Android asking which one on the Home key.
	if cmd package set-home-activity "$OTTO_HOME_APP" >/dev/null 2>&1; then
		otto_log "home: Otto TV's home screen (was ${home:-none})"
	else
		otto_log "home: could not make ours the home screen"
	fi
}

# ---- Apps compiled ahead of time ------------------------------------------------
# Apps from a store are only verified when installed, so their first opens
# run interpreted code on an old CPU (DuckDuckGo took 10 to 37 s on the
# Acer). Once the boot has settled, each is compiled ahead of time
# ("speed"), and so is our own app, the home screen. ART skips what is
# already compiled that way, so later boots only compile what is new.
# OTTO_COMPILE=off turns it off.
otto_precompile() {
	[ "${OTTO_COMPILE:-on}" = on ] || return 0
	# In the detached shell, $0 is the delay and $1 our app.
	# shellcheck disable=SC2016  # expanded by the detached shell
	otto_detach '
		sleep "$0"
		for p in "$1" $(pm list packages -3 2>/dev/null | sed "s/^package://"); do
			out=$(cmd package compile -m speed "$p" 2>&1)
			case "$out" in
			*Success*) log -t otto "compile: $p" ;;
			*) log -t otto "compile: $p: $out" ;;
			esac
		done
	' "${OTTO_COMPILE_DELAY:-180}" "${OTTO_HOME_APP%%/*}"
}

# Android 14 keeps a TV dark until someone answers "Mirror to display?", and
# asks on the laptop's own screen: nobody sees that when the laptop screen is
# dark or its lid is closed. Userdebug builds (this one) can skip the question.
# Set in post_init for this boot and again at boot complete, when persistent
# props are written to disk: OTTO_MIRROR=ask brings the question back from
# the boot after the one it was set on.
OTTO_MIRROR_PROP=persist.sys.display.enable_on_connect.external
otto_setup_mirror() {
	local v=true
	[ "${OTTO_MIRROR:-auto}" = ask ] && v=false
	setprop "$OTTO_MIRROR_PROP" "$v"
}

# Otto in text again, centered, now that Android's graphics driver has the
# screen. The initrd drew it on the firmware's framebuffer. When the driver
# (i915...) replaces that, the console spends a moment on the 80x25 dummy
# console and keeps only that corner of the drawing: the head and the right
# half were gone until the boot animation. post_init runs after upstream's
# init_hal_gralloc, which waits for that driver.
# Usage: otto_console_art [line...] (lines of text under him)
otto_console_art() {
	local art="${OTTO_ART:-/boot/otto/otto-ascii.txt}" tty="${OTTO_CONSOLE:-/dev/tty0}"
	local size rows cols fb pad top spaces line
	[ -f "$art" ] && [ -w "$tty" ] || return 0
	size=$(stty -F "$tty" size 2>/dev/null)
	rows=${size% *}
	cols=${size#* }
	case "$size" in
	[0-9]*" "[0-9]*) ;;
	*)
		# The framebuffer, in the console's 8x16 font.
		fb=$(cat "${OTTO_FB_SIZE:-/sys/class/graphics/fb0/virtual_size}" 2>/dev/null)
		case "$fb" in
		[0-9]*,[0-9]*)
			cols=$((${fb%,*} / 8))
			rows=$((${fb#*,} / 16))
			;;
		*) return 0 ;;
		esac
		;;
	esac
	pad=$(((cols - 76) / 2))
	top=$(((rows - 20) / 2))
	[ $# -eq 0 ] || top=$(((rows - 21 - $#) / 2))
	[ "$pad" -ge 0 ] || pad=0
	[ "$top" -ge 0 ] || top=0
	spaces=$(printf "%${pad}s" "")
	{
		# clear, cursor home, cursor hidden
		printf '\033[2J\033[H\033[?25l'
		while [ "$top" -gt 0 ]; do
			echo
			top=$((top - 1))
		done
		while IFS= read -r line; do
			echo "$spaces$line"
		done <"$art"
		[ $# -eq 0 ] || echo
		for line in "$@"; do
			pad=$(((cols - ${#line}) / 2))
			[ "$pad" -ge 0 ] || pad=0
			printf "%${pad}s%s\n" "" "$line"
		done
	} >"$tty" 2>/dev/null
}

# A first boot, which takes minutes (and every live boot: the live system
# starts from scratch). Upstream writes post_inst_complete at the end of its
# do_bootcomplete, once its bundled apps are installed; the initrd
# (otto_first_boot in 9-otto, text_art) decides the same way.
otto_first_boot() {
	[ ! -e "${OTTO_POST_INST:-/data/vendor/post_inst_complete}" ]
}

# On a first boot the home screen comes up empty when the boot animation
# ends, and stays so for a minute or more: Android unlocks the user, our
# boot-complete step sets things up, the first-run screen opens. An empty
# screen looks stuck, so "Almost ready" (PreparingActivity, in our app)
# covers it: started in the background when the boot animation ends, told
# to go (READY) when boot complete is done with it, which also sets
# OTTO_READY_PROP. The first-run screen covering it closes it too.
OTTO_PREPARING_APP=app.ottotv.connect/.PreparingActivity
OTTO_READY_PROP=otto.boot.ready
otto_show_preparing() {
	# In the detached shell, $0 is the property and $1 the activity.
	# shellcheck disable=SC2016  # expanded by the detached shell
	otto_detach '
		i=0
		until [ "$(getprop service.bootanim.exit)" = 1 ]; do
			[ "$i" -lt 900 ] || exit 0
			sleep 1
			i=$((i + 1))
		done
		[ "$(getprop "$0")" = 1 ] && exit 0
		am start -n "$1" >/dev/null 2>&1 && log -t otto "showing the preparing screen"
		i=0
		until [ "$(getprop "$0")" = 1 ]; do
			[ "$i" -lt 900 ] || exit 0
			sleep 1
			i=$((i + 1))
		done
		# Again a little later, in case it was still opening.
		for i in 1 2 3; do
			am broadcast -a app.ottotv.connect.READY -p app.ottotv.connect >/dev/null 2>&1
			sleep 2
		done
	' "$OTTO_READY_PROP" "$OTTO_PREPARING_APP"
}

post_init() {
	otto_log "post_init"
	if otto_first_boot; then
		otto_console_art "Preparando o Otto TV pela primeira vez." "Isso leva alguns minutos."
		[ "${OTTO_SETUP:-ours}" = wizard ] || otto_show_preparing
	else
		otto_console_art
	fi
	# Persistent props load later and win, so a language the user picked stays.
	set_prop_if_empty persist.sys.locale "${OTTO_LOCALE:-pt-BR}"
	otto_setup_mirror
	otto_setup_gpu
	otto_setup_audio
}

# On an installed system, boot completes before Android unlocks the user,
# and until then apps that don't run before unlock are invisible: ours
# included, so the keyboard looked "not installed" and the pairing screen
# couldn't open. Waits until our keyboard is listed (at once in live mode,
# which has nothing to unlock). Gives up after OTTO_UNLOCK_WAIT seconds.
otto_wait_for_apps() {
	local i=0
	until ime list -a -s 2>/dev/null | grep -qx "$OTTO_HARD_IME"; do
		if [ "$i" -ge "${OTTO_UNLOCK_WAIT:-60}" ]; then
			otto_log "apps: ours still not visible after ${i}s"
			return 1
		fi
		sleep 1
		i=$((i + 1))
	done
	[ "$i" -eq 0 ] || otto_log "apps: visible after ${i}s (user unlocked)"
}

# Called first thing in upstream's do_bootcomplete (9-otto adds the call),
# before upstream installs its bundled apps: what the user touches first
# works as soon as the home screen shows.
otto_bootcomplete_early() {
	local remote=""
	OTTO_EARLY_DONE=1
	otto_log "bootcomplete"
	otto_setup_mirror
	otto_setup_done
	otto_start_remote && remote=1
	otto_wait_for_apps
	otto_setup_home
	otto_precompile
	otto_setup_ime
	[ -z "$remote" ] || otto_offer_pairing
	# "Almost ready" can go (otto_show_preparing).
	setprop "$OTTO_READY_PROP" 1
	# The home screen is up: the boot prefetcher notes what the boot read
	# (otto_prefetch in 9-otto).
	mkdir -p "$OTTO_STATE" && : >"$OTTO_STATE/booted"
}

# The initrd raised disk read-ahead for the boot (otto_prefetch in 9-otto):
# large reads suit a hard disk reading the system image start to end, not
# apps reading here and there. 512 KB from now on.
otto_restore_readahead() {
	local q sys="${OTTO_SYSFS:-/sys}"
	for q in "$sys"/block/sd*/queue "$sys/block/loop0/queue"; do
		[ "$(cat "$q/read_ahead_kb" 2>/dev/null)" = 2048 ] && echo 512 >"$q/read_ahead_kb"
	done 2>/dev/null
	return 0
}

post_bootcomplete() {
	otto_log "post_bootcomplete"
	otto_restore_readahead
	# Only if upstream's init.sh changed shape and the early call is missing.
	[ -n "${OTTO_EARLY_DONE:-}" ] || otto_bootcomplete_early
	# The TV may have been off or still waking up during early boot.
	if [ -z "$(getprop hal.audio.out.hdmi)" ] && otto_setup_audio; then
		otto_log "audio: restarting audioserver to apply"
		pkill audioserver
	fi
}

# ---- end of Otto TV hooks ---------------------------------------------------

# shellcheck shell=sh
# ---- TV Box hooks: prepended to /system/etc/init.sh at boot ----------------
#
# Upstream init.sh calls post_init (end of `init.sh`, run on post-fs, before
# audioserver starts) and post_bootcomplete (end of `init.sh bootcomplete`)
# but never defines them, so we define them here. Before either runs,
# init.sh evaluates every uppercase VAR=value on the kernel command line,
# so options like TVBOX_AUDIO=off arrive as shell variables.
#
# Only function definitions live here: init.sh runs its own code after us.

tvbox_log() {
	# /dev/kmsg works this early (read it with `dmesg`); logcat once logd is up.
	echo "tvbox: $*" >"${TVBOX_KMSG:-/dev/kmsg}" 2>/dev/null
	log -t tvbox "$*" 2>/dev/null
}

# Runs a shell script in the background, outside init.sh's process group:
# init kills a service's whole group (its cgroup) when it exits, and init.sh
# is a oneshot service. Inside the script, $0, $1... are the extra arguments.
# Usage: tvbox_detach '<script>' [args...]
#
# The child is moved out of the cgroup here, before we return. Up to 0.3.3
# the child moved itself, which races init: when tvbox_detach was the last
# thing init.sh did, init could kill the group first. That is how the
# setup-done step died silently, leaving Home and developer options dead.
tvbox_detach() {
	local script="$1" pid g
	shift
	setsid sh -c "$script" "$@" </dev/null >/dev/null 2>&1 &
	pid=$!
	for g in ${TVBOX_CGROUP_PROCS:-/sys/fs/cgroup/cgroup.procs /acct/cgroup.procs}; do
		echo "$pid" >"$g"
	done 2>/dev/null
}

# ---- First-run setup ------------------------------------------------------------
# Our initrd hook hides upstream's setup wizard (see tvbox_hide_setup_wizard).
# Without a wizard nobody marks setup as done, and then Android TV ignores
# the Home key and Settings locks developer options, so we mark it at boot
# complete. (Up to 0.3.4 a background job did it earlier, from post_init;
# it never lived long enough to see the settings service.)
tvbox_setup_done() {
	[ "${TVBOX_SETUP:-ours}" = wizard ] && return 0
	if [ "$(settings get global device_provisioned 2>/dev/null)" = 1 ] &&
		[ "$(settings get secure user_setup_complete 2>/dev/null)" = 1 ]; then
		return 0
	fi
	settings put global device_provisioned 1
	settings put secure user_setup_complete 1
	settings put secure tv_user_setup_complete 1
	tvbox_log "setup: marked as done"
}

# Reads `alsa_amixer -c <card> contents` on stdin and prints pcmC<card>D<dev>p
# for the first HDMI/DP output whose jack reports a connected display, e.g.
#   numid=25,iface=CARD,name='HDMI/DP,pcm=3 Jack'
#     ; type=BOOLEAN,access=r-------,values=1
#     : values=on
tvbox_parse_hdmi_jacks() {
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
# TVBOX_ASOUND_CARDS only exists so tests can point at a fixture.
tvbox_find_hdmi_pcm() {
	local card pcm
	# shellcheck disable=SC2013  # sed prints one bare card number per line
	for card in $(sed -n 's/^ *\([0-9][0-9]*\) \[.*/\1/p' "${TVBOX_ASOUND_CARDS:-/proc/asound/cards}" 2>/dev/null); do
		pcm=$(alsa_amixer -c "$card" contents 2>/dev/null | tvbox_parse_hdmi_jacks "$card")
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
tvbox_setup_audio() {
	local pcm
	if [ "${TVBOX_AUDIO:-auto}" != "auto" ]; then
		tvbox_log "audio: TVBOX_AUDIO=$TVBOX_AUDIO, not touching audio"
		return 1
	fi
	if ! pcm=$(tvbox_find_hdmi_pcm); then
		tvbox_log "audio: no display on HDMI, keeping the default output"
		return 1
	fi
	setprop hal.audio.primary.hdmi 1
	setprop hal.audio.out.hdmi "$pcm"
	tvbox_log "audio: display on HDMI, routing sound to $pcm"
}

# ---- On-screen keyboard ------------------------------------------------------
# Android TV's keyboard (LeanbackIME) shows up even with a physical keyboard
# plugged in: it overrides that check on purpose, so no setting hides it.
# With a physical keyboard we switch to our own (tvapp/, KeyboardService):
# it draws nothing, and Enter does what the TV keyboard's "›" button does,
# which screens like the Wi-Fi password need to continue. Without a physical
# keyboard the TV keyboard stays, since remote-only setups need it to type.

TVBOX_HARD_IME=app.tvbox.connect/.KeyboardService

# Reads /proc/bus/input/devices on stdin and prints the name of every
# keyboard with letter keys (Q to P all present), skipping our own virtual
# devices. The last word of "B: KEY=" holds key codes 0-63, as hex.
tvbox_parse_keyboards() {
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
			if (int(hexval(low) / 65536) % 1024 == 1023 && name !~ /^TV Box Remote/)
				print name
		}
		/^$/ { name = "" }
	'
}

tvbox_setup_ime() {
	local kbd
	case "${TVBOX_IME:-auto}" in
	tv)
		tvbox_log "ime: TVBOX_IME=tv, keeping the TV keyboard"
		return 0
		;;
	hard) kbd="TVBOX_IME=hard" ;;
	*)
		kbd=$(tvbox_parse_keyboards <"${TVBOX_INPUT_DEVICES:-/proc/bus/input/devices}" | head -n 1)
		if [ -z "$kbd" ]; then
			tvbox_log "ime: no physical keyboard, keeping the TV keyboard"
			return 0
		fi
		;;
	esac
	if ! ime list -a -s 2>/dev/null | grep -qx "$TVBOX_HARD_IME"; then
		tvbox_log "ime: $TVBOX_HARD_IME is not installed"
		return 1
	fi
	ime enable "$TVBOX_HARD_IME" >/dev/null 2>&1
	ime set "$TVBOX_HARD_IME" >/dev/null 2>&1
	settings put secure show_ime_with_hard_keyboard 0
	tvbox_log "ime: physical keyboard ($kbd), on-screen keyboard hidden"
}

# ---- Phone remote -------------------------------------------------------------
# tvbox-remote serves the phone remote page and types what it sends through
# virtual input devices. Our initrd hook makes our files visible at
# /boot/tvbox (TVBOX_DIR only exists so tests can point elsewhere).

TVBOX_STATE=/data/misc/tvbox
TVBOX_CONNECT_APP=app.tvbox.connect/.ConnectActivity

tvbox_start_remote() {
	local dir bin="$TVBOX_STATE/tvbox-remote"
	if [ "${TVBOX_REMOTE:-on}" != on ]; then
		tvbox_log "remote: TVBOX_REMOTE=$TVBOX_REMOTE, not starting"
		return 1
	fi
	dir=${TVBOX_DIR:-/boot/tvbox}
	if [ ! -f "$dir/remote/tvbox-remote" ]; then
		tvbox_log "remote: $dir/remote/tvbox-remote not found"
		return 1
	fi
	mkdir -p "$TVBOX_STATE" && chmod 700 "$TVBOX_STATE"
	# Run a copy: an install partition may be mounted noexec on /boot.
	cp "$dir/remote/tvbox-remote" "$bin" && chmod 700 "$bin" || return 1

	# Restarted if it ever exits.
	# shellcheck disable=SC2016  # expanded by the inner shell
	tvbox_detach '
		while :; do
			"$0" -state "$1" >"$1/remote.log" 2>&1
			sleep 3
		done' "$bin" "$TVBOX_STATE"
	tvbox_log "remote: started"
}

# Until a phone is paired, open the "Connect phone" screen: it is also our
# first-run screen, asking for Wi-Fi first when the TV is offline. Live
# sessions keep nothing, so they show it on every boot.
tvbox_offer_pairing() {
	[ -s "$TVBOX_STATE/paired" ] && return 0
	if am start -n "$TVBOX_CONNECT_APP" >/dev/null 2>&1; then
		tvbox_log "remote: showing the pairing screen"
	else
		tvbox_log "remote: could not open the pairing screen"
	fi
}

# Android 14 keeps a TV dark until someone answers "Mirror to display?", and
# asks on the laptop's own screen: nobody sees that when the laptop screen is
# dark or its lid is closed. Userdebug builds (this one) can skip the question.
# Set in post_init for this boot and again at boot complete, when persistent
# props are written to disk: TVBOX_MIRROR=ask brings the question back from
# the boot after the one it was set on.
TVBOX_MIRROR_PROP=persist.sys.display.enable_on_connect.external
tvbox_setup_mirror() {
	local v=true
	[ "${TVBOX_MIRROR:-auto}" = ask ] && v=false
	setprop "$TVBOX_MIRROR_PROP" "$v"
}

post_init() {
	tvbox_log "post_init"
	# Persistent props load later and win, so a language the user picked stays.
	set_prop_if_empty persist.sys.locale "${TVBOX_LOCALE:-pt-BR}"
	tvbox_setup_mirror
	tvbox_setup_audio
}

# On an installed system, boot completes before Android unlocks the user,
# and until then apps that don't run before unlock are invisible: ours
# included, so the keyboard looked "not installed" and the pairing screen
# couldn't open. Waits until our keyboard is listed (at once in live mode,
# which has nothing to unlock). Gives up after TVBOX_UNLOCK_WAIT seconds.
tvbox_wait_for_apps() {
	local i=0
	until ime list -a -s 2>/dev/null | grep -qx "$TVBOX_HARD_IME"; do
		if [ "$i" -ge "${TVBOX_UNLOCK_WAIT:-60}" ]; then
			tvbox_log "apps: ours still not visible after ${i}s"
			return 1
		fi
		sleep 1
		i=$((i + 1))
	done
	[ "$i" -eq 0 ] || tvbox_log "apps: visible after ${i}s (user unlocked)"
}

# Called first thing in upstream's do_bootcomplete (9-tvbox adds the call),
# before upstream installs its bundled apps: what the user touches first
# works as soon as the home screen shows.
tvbox_bootcomplete_early() {
	local remote=""
	TVBOX_EARLY_DONE=1
	tvbox_log "bootcomplete"
	tvbox_setup_mirror
	tvbox_setup_done
	tvbox_start_remote && remote=1
	tvbox_wait_for_apps
	tvbox_setup_ime
	[ -z "$remote" ] || tvbox_offer_pairing
}

post_bootcomplete() {
	tvbox_log "post_bootcomplete"
	# Only if upstream's init.sh changed shape and the early call is missing.
	[ -n "${TVBOX_EARLY_DONE:-}" ] || tvbox_bootcomplete_early
	# The TV may have been off or still waking up during early boot.
	if [ -z "$(getprop hal.audio.out.hdmi)" ] && tvbox_setup_audio; then
		tvbox_log "audio: restarting audioserver to apply"
		pkill audioserver
	fi
}

# ---- end of TV Box hooks ---------------------------------------------------

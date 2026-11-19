#!/system/bin/sh
# Performance baseline for a TV Box machine: the "before" numbers every
# optimization gets compared against. Read-only apart from a 128 MB test
# file it deletes; a reboot undoes the rest (dropped caches, opened apps).
#
#   adb root && adb push tools/device/baseline.sh /data/local/tmp/
#   adb shell sh /data/local/tmp/baseline.sh          # ~4 minutes
#   adb pull /data/local/tmp/<file it names>
#
#   adb shell sh /data/local/tmp/baseline.sh video    # while a video plays
#
# Run it a couple of minutes after the home screen shows up, with nothing
# else open. It opens apps and moves around the home screen by itself.

MODE=${1:-full}
STATE=/data/misc/tvbox
TMP=/data/local/tmp/tvbox-baseline.tmp

say() { echo "$*" >&3; }
section() { echo; echo "===== $* ====="; }
now_cs() { awk '{ printf "%d", $1 * 100 }' /proc/uptime; }
secs() { awk -v a="$1" -v b="$2" 'BEGIN { printf "%.1f", (b - a) / 100 }'; }

version() {
	v=$("$STATE/tvbox-remote" -version 2>/dev/null)
	echo "${v:-unknown}"
}

# Which boot options this run used, so files from different boots don't mix.
label() {
	l=""
	for opt in COPYTORAM=1 FFMPEG_CODEC=1 PC_MODE=1 HWACCEL=0; do
		grep -q "$opt" /proc/cmdline && l="$l-$(echo "${opt%%=*}" | tr '[:upper:]' '[:lower:]')"
	done
	# RAM capped with mem=2G etc., to stand in for smaller machines.
	m=$(tr ' ' '\n' </proc/cmdline | sed -n 's/^mem=\(.*\)/\1/p' | tr '[:upper:]' '[:lower:]')
	[ -n "$m" ] && l="$l-mem$m"
	echo "${l:--padrao}"
}

OUT=/data/local/tmp/tvbox-baseline-$(version)$(label).txt
[ "$MODE" = video ] && OUT=/data/local/tmp/tvbox-video-$(version)$(label).txt

if [ "$(id -u)" != 0 ]; then
	echo "Precisa de root: rode 'adb root', conecte de novo e repita."
	exit 1
fi
exec 3>&1 >"$OUT" 2>&1

# --- Video mode: what playback costs -------------------------------------------
if [ "$MODE" = video ]; then
	say "Medindo o video por 35 segundos, deixa rodando..."
	section video-app
	dumpsys activity activities | grep -m 2 -E 'topResumedActivity|mResumedActivity'
	section video-top
	top -b -d 5 -n 7 -m 10
	section video-gpu
	for f in /sys/class/drm/card0/gt_cur_freq_mhz /sys/class/drm/card0/gt_max_freq_mhz; do
		echo "$f: $(cat "$f" 2>/dev/null)"
	done
	section video-codecs
	logcat -d -v monotonic | grep -iE 'CCodec|Created component|c2\.[a-z]+\.|OMX\.|MediaCodec.*(name|init)|vaapi|ffmpeg' | tail -n 60
	section video-thermal
	for z in /sys/class/thermal/thermal_zone*; do
		echo "$(cat "$z/type" 2>/dev/null) $(cat "$z/temp" 2>/dev/null)"
	done
	say "Pronto. Puxe o arquivo:"
	say "  adb pull $OUT"
	exit 0
fi

# Settle: boot work (app installs, dexopt) skews everything for a while.
up=$(awk '{ printf "%d", $1 }' /proc/uptime)
if [ "$up" -lt 150 ]; then
	say "O sistema ligou ha ${up}s. Esperando ate 150s pra ele assentar..."
	sleep $((150 - up))
fi
say "Medindo (uns 4 minutos). Nao mexe na TV enquanto isso."

section build
getprop ro.build.fingerprint
echo "tvbox $(version)"
uname -a
cat /proc/cmdline
date '+%F %T %z'
cat /proc/uptime

# --- Boot ---------------------------------------------------------------------
section boot-events
logcat -b events -d 2>/dev/null | grep -E 'boot_progress|sf_stop_bootanim|wm_boot_animation_done'
section boot-kernel
dmesg | grep -E 'Run /init|tvbox:|sys\.boot_completed=1|post_bootcomplete' | head -n 80
section boot-props
getprop | grep -E 'boottime|boot_completed'
section boot-tvbox-log
logcat -d -v monotonic -s tvbox 2>/dev/null | head -n 60
section boot-installs
for p in $(pm list packages -3 | sed 's/^package://'); do
	echo "$p: $(dumpsys package "$p" | grep -m 2 -E 'firstInstallTime|lastUpdateTime' | tr -s ' \n' ' ')"
done

# --- Hardware and system settings ----------------------------------------------
section cpu
grep -m 1 'model name' /proc/cpuinfo
grep -c '^processor' /proc/cpuinfo
for f in scaling_driver scaling_governor cpuinfo_min_freq cpuinfo_max_freq scaling_cur_freq energy_performance_preference; do
	echo "$f: $(cat "/sys/devices/system/cpu/cpu0/cpufreq/$f" 2>/dev/null)"
done
for f in /sys/devices/system/cpu/intel_pstate/*; do
	[ -f "$f" ] && echo "$f: $(cat "$f")"
done
section gpu
for f in /sys/class/drm/card0/gt_*freq_mhz; do
	[ -f "$f" ] && echo "${f##*/}: $(cat "$f")"
done
section displays
for c in /sys/class/drm/card0-*; do
	[ -d "$c" ] || continue
	echo "${c##*/}: status=$(cat "$c/status" 2>/dev/null) enabled=$(cat "$c/enabled" 2>/dev/null) modes=$(head -n 4 "$c/modes" 2>/dev/null | tr '\n' ' ')"
done
wm size
wm density
dumpsys display | grep -E 'DisplayDeviceInfo\{|mActiveSfDisplayMode' | head -n 10
section surfaceflinger
dumpsys SurfaceFlinger | grep -iE 'GLES|refresh|composition|hwc|Display [0-9]+ ' | head -n 30
section graphics-props
getprop | grep -iE 'egl|vulkan|gralloc|hwcomposer|hwui|renderer|sf\.|opengles|mesa'
section media
getprop | grep -iE 'ffmpeg|codec2|media\.|hwaccel'
ls /vendor/etc/media_codecs*.xml /system/etc/media_codecs*.xml 2>/dev/null
grep -h '<MediaCodec ' /vendor/etc/media_codecs*.xml /system/etc/media_codecs*.xml 2>/dev/null |
	grep -i 'video/' | sed 's/^[[:space:]]*//' | sort -u | head -n 60
section memory-config
getprop | grep -iE 'low_ram|lmk|dalvik\.vm|zram|swap|ro\.config\.|max_cached|fw\.'
cat /proc/swaps
for f in disksize comp_algorithm mm_stat; do
	echo "zram0 $f: $(cat "/sys/block/zram0/$f" 2>/dev/null)"
done
echo "swappiness: $(cat /proc/sys/vm/swappiness)"
section storage
mount | grep -E ' /data | /system | /vendor | /product | /storage|loop|tmpfs on /data'
df -h /data /system /vendor 2>/dev/null
for l in /sys/block/loop*; do
	f=$(cat "$l/loop/backing_file" 2>/dev/null)
	[ -n "$f" ] && echo "${l##*/}: $f ($(cat "$l/size") sectors)"
done
for q in /sys/block/sd*/queue; do
	[ -d "$q" ] && echo "$q: scheduler=$(cat "$q/scheduler") read_ahead_kb=$(cat "$q/read_ahead_kb") rotational=$(cat "$q/rotational")"
done
section settings
for k in window_animation_scale transition_animation_scale animator_duration_scale; do
	echo "$k=$(settings get global "$k")"
done
section packages
echo "system: $(pm list packages -s | wc -l)  third-party: $(pm list packages -3 | wc -l)  disabled: $(pm list packages -d | wc -l)"
pm list packages -s -f | sed 's/^package://' | sort
section props
getprop

# --- Memory and idle load -------------------------------------------------------
section meminfo
cat /proc/meminfo
section meminfo-processes
dumpsys meminfo | sed -n '/Total PSS by process/,/Total PSS by OOM/p' | head -n 45
dumpsys meminfo | grep -E 'Total RAM|Free RAM|Used RAM|Lost RAM|ZRAM'
section idle-top
top -b -d 3 -n 2 -m 15
cat /proc/loadavg
section thermal-idle
for z in /sys/class/thermal/thermal_zone*; do
	echo "$(cat "$z/type" 2>/dev/null) $(cat "$z/temp" 2>/dev/null)"
done

# --- CPU and storage speed ----------------------------------------------------------
section bench-cpu
t0=$(now_cs)
dd if=/dev/zero bs=1048576 count=256 2>/dev/null | sha256sum >/dev/null
t1=$(now_cs)
cpu_s=$(secs "$t0" "$t1")
echo "sha256 of 256 MB: ${cpu_s}s"

# Reads straight from the boot media (USB stick in live mode), then through
# the compressed system image, from a cold cache.
read_mbs() {
	sync
	echo 3 >/proc/sys/vm/drop_caches
	t0=$(now_cs)
	dd if="$1" of=/dev/null bs=1048576 count=128 2>/dev/null
	t1=$(now_cs)
	awk -v a="$t0" -v b="$t1" 'BEGIN { if (b > a) printf "%.0f", 128 * 100 / (b - a); else print "?" }'
}
section bench-storage
raw_mbs="?" sys_mbs="?"
for l in /sys/block/loop*; do
	f=$(cat "$l/loop/backing_file" 2>/dev/null)
	case "$f" in
	*system.efs* | *SYSTEM.EFS*) raw_mbs=$(read_mbs "/dev/block/${l##*/}") ;;
	*system.img*) sys_mbs=$(read_mbs "/dev/block/${l##*/}") ;;
	esac
done
echo "boot media read: $raw_mbs MB/s, system image read: $sys_mbs MB/s"
t0=$(now_cs)
dd if=/dev/zero of="$TMP" bs=1048576 count=128 conv=fsync 2>/dev/null
t1=$(now_cs)
rm -f "$TMP"
data_mbs=$(awk -v a="$t0" -v b="$t1" 'BEGIN { if (b > a) printf "%.0f", 128 * 100 / (b - a); else print "?" }')
echo "/data write: $data_mbs MB/s"

# --- Network ------------------------------------------------------------------------
section network
cmd wifi status 2>/dev/null | head -n 25
gw=$(ip route get 1.1.1.1 2>/dev/null | awk '{ for (i = 1; i < NF; i++) if ($i == "via") print $(i + 1) }' | head -n 1)
echo "gateway: ${gw:-none}"
ping_avg() {
	ping -c 10 -i 0.3 -W 2 "$1" 2>/dev/null | tee -a "$OUT.ping" | awk -F'/' '/^(rtt|round-trip)/ { print $5 }'
}
lan_ms=$([ -n "$gw" ] && ping_avg "$gw")
wan_ms=$(ping_avg 1.1.1.1)
loss=$(grep -o '[0-9.]*% packet loss' "$OUT.ping" 2>/dev/null | tail -n 1)
cat "$OUT.ping" 2>/dev/null
rm -f "$OUT.ping"

# --- App start times ----------------------------------------------------------------
component() {
	for c in android.intent.category.LEANBACK_LAUNCHER android.intent.category.LAUNCHER; do
		r=$(cmd package resolve-activity --brief -a android.intent.action.MAIN -c "$c" "$1" 2>/dev/null | tail -n 1)
		case "$r" in */*) echo "$r"; return 0 ;; esac
	done
	# Settings apps have no launcher entry, only the settings action.
	r=$(cmd package resolve-activity --brief -a android.settings.SETTINGS "$1" 2>/dev/null | tail -n 1)
	case "$r" in */*) echo "$r"; return 0 ;; esac
	return 1
}
section app-starts
apps_summary=""
for p in com.android.tv.settings app.tvbox.connect org.fdroid.fdroid com.aurora.store com.duckduckgo.mobile.android com.stremio.one; do
	comp=$(component "$p") || continue
	times=""
	for _ in 1 2 3; do
		t=$(am start -W -S -n "$comp" 2>/dev/null | awk -F': ' '/TotalTime/ { print $2 }')
		times="$times ${t:-?}"
		sleep 3
	done
	med=$(echo "$times" | tr ' ' '\n' | grep -E '^[0-9]+$' | sort -n | sed -n 2p)
	echo "$p ($comp): cold starts ms:$times, median ${med:-?}"
	apps_summary="$apps_summary
  $p: ${med:-?} ms"
	am force-stop "$p"
done
input keyevent HOME
sleep 3

# --- Multitasking: how many apps Android keeps in memory ------------------------
# Opens apps one after another, then goes back to each: HOT or WARM means it
# was still in memory, COLD means Android had dropped it and started over.
# This is what low RAM, zram and Android Go's limits change.
section multitask
mt=""
for p in org.fdroid.fdroid com.aurora.store com.duckduckgo.mobile.android com.android.tv.settings app.tvbox.connect com.stremio.one me.zhanghai.android.files; do
	c=$(component "$p") && mt="$mt $c"
done
for c in $mt; do
	am start -W -n "$c" >/dev/null 2>&1
	sleep 5
done
input keyevent HOME
sleep 3
kept=0 total=0 back=""
for c in $mt; do
	r=$(am start -W -n "$c" 2>/dev/null)
	st=$(echo "$r" | awk -F': ' '/LaunchState/ { print $2 }')
	t=$(echo "$r" | awk -F': ' '/TotalTime/ { print $2 }')
	echo "$c: ${st:-?} ${t:-?} ms"
	total=$((total + 1))
	case "$st" in HOT | WARM) kept=$((kept + 1)) ;; esac
	back="$back ${t:-}"
	sleep 3
done
# "Kept in memory" means little if coming back still takes seconds: with
# too little RAM the app's code gets evicted and re-read from disk.
back_med=$(echo "$back" | tr ' ' '\n' | grep -E '^[0-9]+$' | sort -n | awk '{ v[NR] = $1 } END { if (NR) print v[int((NR + 1) / 2)] }')
back_max=$(echo "$back" | tr ' ' '\n' | grep -E '^[0-9]+$' | sort -n | tail -n 1)
echo "back to an app: median ${back_med:-?} ms, worst ${back_max:-?} ms"
grep -E 'MemAvailable|SwapTotal|SwapFree' /proc/meminfo
input keyevent HOME
sleep 3

# --- Home screen smoothness -----------------------------------------------------
section home-gfxinfo
home=$(cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME 2>/dev/null | tail -n 1)
hpkg=${home%%/*}
echo "home: $home"
dumpsys gfxinfo "$hpkg" reset >/dev/null
press() {
	n=$2
	while [ "$n" -gt 0 ]; do
		input keyevent "$1"
		n=$((n - 1))
	done
}
press DPAD_RIGHT 10
press DPAD_DOWN 2
press DPAD_LEFT 10
press DPAD_DOWN 2
press DPAD_RIGHT 10
press DPAD_UP 4
dumpsys gfxinfo "$hpkg" >"$TMP"
grep -E 'Total frames|Janky frames|percentile|Number ' "$TMP"
frames=$(awk -F': ' '/Total frames rendered/ { print $2; exit }' "$TMP")
janky=$(awk -F': ' '/^Janky frames:/ { print $2; exit }' "$TMP")
p90=$(awk -F': ' '/^90th percentile/ { print $2; exit }' "$TMP")
rm -f "$TMP"
input keyevent HOME

section thermal-after
for z in /sys/class/thermal/thermal_zone*; do
	echo "$(cat "$z/type" 2>/dev/null) $(cat "$z/temp" 2>/dev/null)"
done

# --- Summary --------------------------------------------------------------------------
screen_ms=$(logcat -b events -d 2>/dev/null | awk '/boot_progress_enable_screen/ { print $NF; exit }')
done_s=$(dmesg | grep -m 1 'sys\.boot_completed=1' | sed -n 's/^\[ *\([0-9.]*\)\].*/\1/p')
mem() { awk -v k="$1:" '$1 == k { printf "%d", $2 / 1024 }' /proc/meminfo; }
idle=$(top -b -d 3 -n 2 -m 1 | awk '/%cpu/ { line = $0 } END { print line }' |
	awk '{ for (i = 1; i <= NF; i++) { if ($i ~ /%cpu$/) c = $i + 0; if ($i ~ /%idle$/) d = $i + 0 } if (c) printf "%.0f", (c - d) * 100 / c }')
temps=$(for z in /sys/class/thermal/thermal_zone*; do
	t=$(cat "$z/temp" 2>/dev/null) && echo "$(cat "$z/type")=$((t / 1000))C"
done | tr '\n' ' ')

section summary
SUMMARY=/data/local/tmp/tvbox-summary.tmp
{
	echo "TV Box $(version) $(label | cut -c2-) | $(date '+%F %T')"
	echo "Boot: tela inicial ${screen_ms:-?} ms apos o kernel, boot completo ${done_s:-?} s"
	echo "RAM: total $(mem MemTotal) MB, disponivel $(mem MemAvailable) MB, swap $(mem SwapTotal) MB (livre $(mem SwapFree) MB)"
	echo "Abrir apps a frio (mediana de 3):$apps_summary"
	echo "Multitarefa: $kept de $total apps continuaram na memoria; voltar pra eles: mediana ${back_med:-?} ms, pior ${back_max:-?} ms"
	echo "Tela inicial: ${frames:-?} frames, travados ${janky:-?}, p90 ${p90:-?}"
	echo "CPU: sha256 de 256 MB em ${cpu_s}s, uso parado ${idle:-?}%"
	echo "Armazenamento: midia de boot $raw_mbs MB/s, imagem do sistema $sys_mbs MB/s, /data $data_mbs MB/s"
	echo "Rede: roteador ${lan_ms:-?} ms, internet ${wan_ms:-?} ms (${loss:-perda ?} pra internet)"
	echo "Temperatura: $temps"
} >"$SUMMARY"
cat "$SUMMARY"
cat "$SUMMARY" >&3
rm -f "$SUMMARY"

say ""
say "Pronto. Puxe o arquivo:"
say "  adb pull $OUT"

#!/system/bin/sh
# Records what the machine does, for the performance work (0.6): a trace of
# the home screen while it is used, or of a whole boot. Read the traces at
# ui.perfetto.dev, or with trace_processor.
#
#   adb root && adb push tools/device/trace.sh /data/local/tmp/
#
#   adb shell sh /data/local/tmp/trace.sh home      # ~30 s, moves around the home screen
#   adb pull /data/local/tmp/otto-home.tar
#
#   adb shell sh /data/local/tmp/trace.sh boot      # arms a boot trace and restarts
#   (once the home screen is back, wait 2 minutes, then adb root again)
#   adb shell sh /data/local/tmp/trace.sh boot-done
#   adb pull /data/local/tmp/otto-boot.tar
#
# Needs root (adb root). Changes nothing that outlasts a reboot.

OUT=/data/local/tmp
TRACES=/data/misc/perfetto-traces
CONFIGS=/data/misc/perfetto-configs
APP=app.ottotv.connect

say() { echo "$*"; }
die() { echo "erro: $*" >&2; exit 1; }

[ "$(id -u)" = 0 ] || die "rode adb root antes"
command -v perfetto >/dev/null || die "este Android nao tem perfetto"

# Scheduling, CPU and GPU frequency, and Android's own trace points: frames
# (gfx, view), input, activities and windows (am, wm). Boots add disk I/O:
# EXTRA_EVENTS for more ftrace events, arguments for more atrace categories.
ftrace_config() { # <extra atrace categories...>
	cat <<EOF
data_sources: {
  config {
    name: "linux.ftrace"
    ftrace_config {
      ftrace_events: "sched/sched_switch"
      ftrace_events: "sched/sched_wakeup"
      ftrace_events: "sched/sched_waking"
      ftrace_events: "sched/sched_process_exit"
      ftrace_events: "sched/sched_process_free"
      ftrace_events: "task/task_newtask"
      ftrace_events: "task/task_rename"
      ftrace_events: "power/cpu_frequency"
      ftrace_events: "power/cpu_idle"
      ftrace_events: "power/gpu_frequency"
      ftrace_events: "ftrace/print"
EOF
	for e in ${EXTRA_EVENTS:-}; do
		echo "      ftrace_events: \"$e\""
	done
	cat <<EOF
      atrace_categories: "gfx"
      atrace_categories: "view"
      atrace_categories: "input"
      atrace_categories: "am"
      atrace_categories: "wm"
      atrace_categories: "hal"
      atrace_categories: "res"
      atrace_categories: "dalvik"
      atrace_categories: "binder_driver"
EOF
	for c in "$@"; do
		echo "      atrace_categories: \"$c\""
	done
	cat <<EOF
      atrace_apps: "*"
      buffer_size_kb: 16384
      drain_period_ms: 250
    }
  }
}
data_sources: {
  config {
    name: "linux.process_stats"
    target_buffer: 1
    process_stats_config {
      scan_all_processes_on_start: true
      proc_stats_poll_ms: 1000
    }
  }
}
data_sources: { config { name: "android.surfaceflinger.frametimeline" } }
data_sources: { config { name: "linux.sys_stats" sys_stats_config { meminfo_period_ms: 1000 vmstat_period_ms: 1000 } } }
EOF
}

key() { # <keycode> <seconds to wait after>
	input keyevent "$1"
	sleep "$2"
}

home() {
	cfg=$OUT/otto-home.pbtxt
	trace=$TRACES/otto-home.perfetto-trace
	{
		echo 'buffers: { size_kb: 65536 fill_policy: RING_BUFFER }'
		echo 'buffers: { size_kb: 4096 fill_policy: RING_BUFFER }'
		echo 'duration_ms: 30000'
		ftrace_config
	} >"$cfg"
	say "Gravando a tela inicial (uns 30 segundos). Nao mexe na TV."
	key KEYCODE_HOME 3
	dumpsys gfxinfo "$APP" reset >/dev/null 2>&1
	rm -f "$trace"
	pid=$(perfetto --background --txt -c "$cfg" -o "$trace") || die "o perfetto nao comecou"
	sleep 3
	# Along the apps row and the favorites, like someone choosing what to watch.
	for k in RIGHT RIGHT RIGHT RIGHT LEFT LEFT UP RIGHT RIGHT RIGHT LEFT LEFT LEFT DOWN RIGHT RIGHT UP UP; do
		key "KEYCODE_DPAD_$k" 0.7
	done
	# The quick panel, opened and closed.
	key KEYCODE_F2 1.5
	key KEYCODE_DPAD_DOWN 0.7
	key KEYCODE_DPAD_DOWN 0.7
	key KEYCODE_BACK 1.5
	while kill -0 "$pid" 2>/dev/null; do sleep 1; done
	dumpsys gfxinfo "$APP" framestats >"$OUT/otto-home-gfxinfo.txt" 2>&1
	pack otto-home "$trace" "$OUT/otto-home-gfxinfo.txt"
}

boot_arm() {
	mkdir -p "$CONFIGS"
	{
		echo 'buffers: { size_kb: 196608 fill_policy: DISCARD }'
		echo 'buffers: { size_kb: 8192 fill_policy: DISCARD }'
		echo 'duration_ms: 120000'
		EXTRA_EVENTS="block/block_rq_issue block/block_rq_complete" ftrace_config pm ss disk
	} >"$CONFIGS/boottrace.pbtxt"
	chmod 644 "$CONFIGS/boottrace.pbtxt"
	rm -f "$TRACES/boottrace.perfetto-trace"
	setprop persist.debug.perfetto.boottrace 1
	say "Pronto: a proxima inicializacao vai ser gravada. Reiniciando..."
	sync
	reboot
}

boot_done() {
	trace=$TRACES/boottrace.perfetto-trace
	[ -s "$trace" ] || say "aviso: sem trace do boot (este Android pode nao ter boot trace); juntando o resto"
	# The trace runs for 2 minutes from early boot.
	i=0
	while pidof perfetto >/dev/null 2>&1 && [ "$i" -lt 180 ]; do
		[ "$i" = 0 ] && say "Esperando a gravacao terminar..."
		sleep 2
		i=$((i + 2))
	done
	dmesg >"$OUT/otto-boot-dmesg.txt" 2>&1
	logcat -b events -d >"$OUT/otto-boot-events.txt" 2>&1
	logcat -b main -b system -d -v uptime >"$OUT/otto-boot-logcat.txt" 2>&1
	getprop >"$OUT/otto-boot-props.txt" 2>&1
	set --
	[ -s "$trace" ] && set -- "$trace"
	pack otto-boot "$@" "$OUT/otto-boot-dmesg.txt" "$OUT/otto-boot-events.txt" \
		"$OUT/otto-boot-logcat.txt" "$OUT/otto-boot-props.txt"
}

pack() { # <name> <files...>
	name=$1
	shift
	dir=$OUT/$name
	rm -rf "$dir" "$OUT/$name.tar"
	mkdir -p "$dir"
	cp "$@" "$dir/" 2>/dev/null
	cat /proc/cmdline >"$dir/cmdline.txt"
	"/data/misc/otto/otto-remote" -version >"$dir/version.txt" 2>&1
	(cd "$OUT" && tar -cf "$name.tar" "$name") || die "nao consegui juntar os arquivos"
	chmod 644 "$OUT/$name.tar"
	rm -rf "$dir"
	say "Pronto. Puxe o arquivo:"
	say "  adb pull $OUT/$name.tar"
}

case "${1:-}" in
home) home ;;
boot) boot_arm ;;
boot-done) boot_done ;;
*) die "uso: sh trace.sh home | boot | boot-done" ;;
esac

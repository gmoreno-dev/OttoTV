#!/system/bin/sh
# Collects what an installed system kept about earlier crashes (native
# tombstones, system_server crashes in the dropbox) plus our boot log.
#
#   adb root && adb push tools/device/crash.sh /data/local/tmp/
#   adb shell sh /data/local/tmp/crash.sh
#   adb pull /data/local/tmp/crash.txt

# Android names these files itself (no spaces), and ls -t sorts newest first.
# shellcheck disable=SC2010,SC2012
OUT=/data/local/tmp/crash.txt
{
	echo "===== tombstones"
	ls -lt /data/tombstones 2>&1 | head -n 20
	echo "===== dropbox"
	ls -lt /data/system/dropbox 2>&1 | head -n 40
	for f in $(ls -t /data/tombstones/tombstone_* 2>/dev/null | grep -v '\.pb$' | head -n 3); do
		echo "===== $f"
		head -n 120 "$f"
	done
	for f in $(ls -t /data/system/dropbox 2>/dev/null |
		grep -E 'system_server_crash|system_server_native_crash|SYSTEM_TOMBSTONE|system_app_crash|SYSTEM_RESTART|system_server_wtf' |
		head -n 8); do
		echo "===== dropbox/$f"
		case "$f" in
		*.gz) zcat "/data/system/dropbox/$f" | head -n 120 ;;
		*) head -n 120 "/data/system/dropbox/$f" ;;
		esac
	done
	echo "===== pstore"
	ls /sys/fs/pstore 2>&1
	for f in /sys/fs/pstore/*; do
		[ -f "$f" ] && tail -n 60 "$f"
	done
	echo "===== otto"
	dmesg | grep 'otto:'
} >"$OUT" 2>&1
echo "Pronto. Puxe o arquivo:"
echo "  adb pull $OUT"

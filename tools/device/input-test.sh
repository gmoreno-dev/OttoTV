#!/system/bin/sh
# Input diagnostics and a live trial of the keyboard fixes planned for v0.3.
#
#   adb root && adb push tools/device/input-test.sh /data/local/tmp/
#   adb shell sh /data/local/tmp/input-test.sh
#   adb pull /data/local/tmp/tvbox-input.txt
#   adb shell "stop; start"     # restarts the UI so the new key map loads
#
# Everything it changes is temporary: a reboot undoes it.
#   - on-screen keyboard hidden while a physical keyboard is present
#   - Esc on the built-in keyboard sends Back

OUT=/data/local/tmp/tvbox-input.txt
exec 3>&1 >"$OUT" 2>&1
say() { echo "$*" >&3; }
section() { echo; echo "===== $* ====="; }

if [ "$(id -u)" != 0 ]; then
	say "Precisa de root: rode 'adb root', conecte de novo e repita."
	exit 1
fi

say "Coletando informacoes..."

section build
getprop ro.build.fingerprint
getenforce

section input
dumpsys input | head -n 700

section keylayout
ls -l /system/usr/keylayout /vendor/usr/keylayout /odm/usr/keylayout \
	/product/usr/keylayout /data/system/devices/keylayout 2>&1

section keychars
ls -l /system/usr/keychars 2>&1
grep -n -A4 'key ESCAPE' /system/usr/keychars/Generic.kcm

section ime
for k in default_input_method enabled_input_methods show_ime_with_hard_keyboard; do
	echo "$k=$(settings get secure "$k")"
done
ime list -s
dumpsys input_method | grep -iE 'hard|mInputShown|mShowRequested|mCurMethodId' | head -n 20

section config
am get-config 2>&1 | head -n 5
dumpsys window | grep -i keyboard | head -n 20

section getevent
getevent -p 2>&1 | head -n 400

section appops
for p in com.aurora.store org.fdroid.fdroid; do
	echo "$p: $(appops get "$p" REQUEST_INSTALL_PACKAGES)"
done

section tvbox
dmesg | grep tvbox

# --- Live trial 1: no on-screen keyboard while a physical one is present ----
section trial-ime
settings put secure show_ime_with_hard_keyboard 0
echo "show_ime_with_hard_keyboard=$(settings get secure show_ime_with_hard_keyboard)"

# --- Live trial 2: Esc on the built-in keyboard sends Back -------------------
# Use whichever key layout file Android picked for the laptop keyboard.
section trial-esc
kl=$(dumpsys input | awk '
	/^ *-?[0-9]+: / { dev = $0 }
	/KeyLayoutFile:/ && dev ~ /AT Translated Set 2 keyboard/ { print $2; exit }')
[ -f "$kl" ] || kl=/system/usr/keylayout/Generic.kl
echo "laptop keyboard layout file: $kl"

dir=/data/local/tmp/tvbox-kl
mkdir -p "$dir"
new="$dir/$(basename "$kl")"
sed -E 's/^key[[:space:]]+1[[:space:]]+ESCAPE.*/key 1     BACK/' "$kl" >"$new"
grep -qE '^key[[:space:]]+1[[:space:]]+BACK' "$new" || echo 'key 1     BACK' >>"$new"
chmod 644 "$new"
chcon u:object_r:system_file:s0 "$new"
umount "$kl" 2>/dev/null
if mount -o bind "$new" "$kl"; then
	echo "bind mounted $new over $kl"
	grep -nE '^key[[:space:]]+1[[:space:]]' "$kl"
	say "Pronto. Agora puxe o arquivo e reinicie a interface:"
	say "  adb pull /data/local/tmp/tvbox-input.txt"
	say "  adb shell \"stop; start\""
else
	echo "bind mount failed"
	say "Coletei as informacoes, mas nao consegui aplicar o teste do Esc."
	say "  adb pull /data/local/tmp/tvbox-input.txt"
fi

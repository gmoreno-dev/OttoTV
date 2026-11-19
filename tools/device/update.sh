#!/system/bin/sh
# Updates Otto TV's own files on an installed system, without reinstalling.
#
#   adb root
#   adb push otto-<version>-update.tar update.sh /data/local/tmp/
#   adb shell sh /data/local/tmp/update.sh /data/local/tmp/otto-<version>-update.tar
#   adb reboot
#
# The installer put /scripts and /otto next to the kernel on the install
# partition, and the initrd reads them from there on every boot. This mounts
# that partition a second time, replaces our files in /scripts and swaps
# /otto for the new one. It keeps otto/user_app (those apps are installed
# already) and leaves the old folder as otto.old until the next update: the
# running system still uses it. Android itself, your apps and your settings
# aren't touched.

TAR=$1
CMDLINE=${OTTO_CMDLINE:-/proc/cmdline}
MNT=${OTTO_MNT:-/data/local/tmp/otto-root}

fail() {
	echo "erro: $*"
	exit 1
}

[ -n "$TAR" ] && [ -f "$TAR" ] || fail "uso: sh update.sh <otto-VERSAO-update.tar>"
[ "$(id -u)" = 0 ] || fail "precisa de root: rode 'adb root', conecte de novo e repita"
grep -q 'androidboot.live=true' "$CMDLINE" &&
	fail "isto e um sistema live (pendrive): nao ha nada instalado para atualizar"

arg() { tr ' ' '\n' <"$CMDLINE" | sed -n "s/^$1=//p" | head -n 1; }
root=$(arg ROOT)
src=$(arg SRC)
case "$root" in
UUID=*)
	uuid=${root#UUID=}
	dev=$(blkid | grep "UUID=\"$uuid\"" | head -n 1 | cut -d: -f1)
	;;
/dev/*) dev=$root ;;
*) dev="" ;;
esac
[ -n "$dev" ] && [ -e "$dev" ] || fail "nao achei a particao da instalacao (ROOT=$root)"

mkdir -p "$MNT"
mount -t ext4 "$dev" "$MNT" 2>/dev/null || mount "$dev" "$MNT" || fail "nao consegui montar $dev"
trap 'sync; umount "$MNT" 2>/dev/null' EXIT

target="$MNT$src"
[ -f "$target/scripts/9-otto" ] && [ -d "$target/otto" ] ||
	fail "nao achei os arquivos do Otto TV em $dev$src"

new="$target/.otto-update"
rm -rf "$new"
mkdir -p "$new" || fail "sem espaco em $dev?"
tar -xf "$TAR" -C "$new" || fail "nao consegui abrir $TAR"
[ -f "$new/scripts/9-otto" ] && [ -d "$new/otto" ] || fail "$TAR nao parece uma atualizacao do Otto TV"

version() { "$1/otto/remote/otto-remote" -version 2>/dev/null || echo "?"; }
old_version=$(version "$target")
new_version=$(version "$new")

# /scripts may hold other people's hooks too: only our files are replaced.
cp "$new"/scripts/* "$target/scripts/" || fail "nao consegui copiar os scripts"

# /otto is all ours. The previous update's leftovers are no longer in use
# (this boot runs from the current folder), the current one still is.
rm -rf "$target/otto.old"
if [ -d "$target/otto/user_app" ] && [ ! -d "$new/otto/user_app" ]; then
	mv "$target/otto/user_app" "$new/otto/user_app" || fail "nao consegui manter otto/user_app"
fi
mv "$target/otto" "$target/otto.old" || fail "nao consegui trocar a pasta otto"
if ! mv "$new/otto" "$target/otto"; then
	mv "$target/otto.old" "$target/otto"
	fail "nao consegui trocar a pasta otto (a antiga foi mantida)"
fi
# The boot screen lives in the initrd. Installed systems boot initrd<slot>.img
# (initrd_a.img), the one GRUB names; the previous one stays as .old.
if [ -f "$new/initrd.img" ]; then
	initrd="$target/initrd$(arg androidboot.slot_suffix).img"
	[ -f "$initrd" ] || initrd="$target/initrd.img"
	if [ -f "$initrd" ]; then
		cp "$initrd" "$initrd.old" &&
			cp "$new/initrd.img" "$initrd.new" &&
			mv "$initrd.new" "$initrd" || fail "nao consegui trocar $initrd (o anterior esta em $initrd.old)"
	fi
fi
rm -rf "$new"

echo "Otto TV atualizado: $old_version -> $new_version ($dev$src)"
echo "Reinicie para usar: adb reboot"

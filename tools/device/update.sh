#!/system/bin/sh
# Updates TV Box's own files on an installed system, without reinstalling.
#
#   adb root
#   adb push tvbox-<version>-update.tar update.sh /data/local/tmp/
#   adb shell sh /data/local/tmp/update.sh /data/local/tmp/tvbox-<version>-update.tar
#   adb reboot
#
# The installer put /scripts and /tvbox next to the kernel on the install
# partition, and the initrd reads them from there on every boot. This mounts
# that partition a second time, replaces our files in /scripts and swaps
# /tvbox for the new one. It keeps tvbox/user_app (those apps are installed
# already) and leaves the old folder as tvbox.old until the next update: the
# running system still uses it. Android itself, your apps and your settings
# aren't touched.

TAR=$1
CMDLINE=${TVBOX_CMDLINE:-/proc/cmdline}
MNT=${TVBOX_MNT:-/data/local/tmp/tvbox-root}

fail() {
	echo "erro: $*"
	exit 1
}

[ -n "$TAR" ] && [ -f "$TAR" ] || fail "uso: sh update.sh <tvbox-VERSAO-update.tar>"
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
[ -f "$target/scripts/9-tvbox" ] && [ -d "$target/tvbox" ] ||
	fail "nao achei os arquivos do TV Box em $dev$src"

new="$target/.tvbox-update"
rm -rf "$new"
mkdir -p "$new" || fail "sem espaco em $dev?"
tar -xf "$TAR" -C "$new" || fail "nao consegui abrir $TAR"
[ -f "$new/scripts/9-tvbox" ] && [ -d "$new/tvbox" ] || fail "$TAR nao parece uma atualizacao do TV Box"

version() { "$1/tvbox/remote/tvbox-remote" -version 2>/dev/null || echo "?"; }
old_version=$(version "$target")
new_version=$(version "$new")

# /scripts may hold other people's hooks too: only our files are replaced.
cp "$new"/scripts/* "$target/scripts/" || fail "nao consegui copiar os scripts"

# /tvbox is all ours. The previous update's leftovers are no longer in use
# (this boot runs from the current folder), the current one still is.
rm -rf "$target/tvbox.old"
if [ -d "$target/tvbox/user_app" ] && [ ! -d "$new/tvbox/user_app" ]; then
	mv "$target/tvbox/user_app" "$new/tvbox/user_app" || fail "nao consegui manter tvbox/user_app"
fi
mv "$target/tvbox" "$target/tvbox.old" || fail "nao consegui trocar a pasta tvbox"
if ! mv "$new/tvbox" "$target/tvbox"; then
	mv "$target/tvbox.old" "$target/tvbox"
	fail "nao consegui trocar a pasta tvbox (a antiga foi mantida)"
fi
rm -rf "$new"

echo "TV Box atualizado: $old_version -> $new_version ($dev$src)"
echo "Reinicie para usar: adb reboot"

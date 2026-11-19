#!/usr/bin/env bash
# Builds Otto TV's initrd from upstream's: the same archive, followed by a
# second one that replaces /init and the fstab script with quieter copies
# (patch_initrd_init.py) and adds Otto in ASCII. The kernel unpacks concatenated archives in order
# and later files overwrite earlier ones, so upstream's archive is kept
# byte for byte and nothing needs root to unpack.
#
# Usage: tools/patch-initrd.sh <upstream initrd.img> <output initrd.img>
set -euo pipefail

src=$1
out=$2
ROOT=$(cd "$(dirname "$0")/.." && pwd)
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT

mkdir "$work/root"
# Upstream's names start with "./"; either way they land under $work/root.
fstab=scripts/4-fstab-fallback
(cd "$work/root" && gzip -dc "$src" | cpio -i -d --quiet init ./init "$fstab" "./$fstab")
for f in init "$fstab"; do
	[ -f "$work/root/$f" ] || { echo "patch-initrd: no /$f in $src" >&2; exit 1; }
done
python3 "$ROOT/tools/patch_initrd_init.py" "$work/root/init"
# Live systems have no fstab.android, so every boot announced building one.
grep -q '^  echo "Warning: No fstab found. Attempting to create one..."$' "$work/root/$fstab" ||
	{ echo "patch-initrd: $fstab changed upstream" >&2; exit 1; }
sed -i 's/^  echo "Warning: No fstab found. Attempting to create one..."$/  : # no fstab.android: building one/' \
	"$work/root/$fstab"
cp "$ROOT/art/brand/otto-ascii.txt" "$work/root/otto-ascii.txt"
chmod 755 "$work/root/init"
chmod 644 "$work/root/otto-ascii.txt" "$work/root/$fstab"
(cd "$work/root" && printf '%s\n' init "$fstab" otto-ascii.txt |
	cpio -o -H newc -R 0:0 --quiet | gzip -9n) >"$work/otto.cpio.gz"
cat "$src" "$work/otto.cpio.gz" >"$out"

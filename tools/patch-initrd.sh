#!/usr/bin/env bash
# Builds Otto TV's initrd from upstream's: the same archive, followed by a
# second one that replaces /init with a quieter copy (patch_initrd_init.py)
# and adds Otto in ASCII. The kernel unpacks concatenated archives in order
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
(cd "$work/root" && gzip -dc "$src" | cpio -i --quiet init) ||
	{ echo "patch-initrd: no /init in $src" >&2; exit 1; }
python3 "$ROOT/tools/patch_initrd_init.py" "$work/root/init"
cp "$ROOT/art/brand/otto-ascii.txt" "$work/root/otto-ascii.txt"
chmod 755 "$work/root/init"
chmod 644 "$work/root/otto-ascii.txt"
(cd "$work/root" && printf '%s\n' init otto-ascii.txt |
	cpio -o -H newc -R 0:0 --quiet | gzip -9n) >"$work/otto.cpio.gz"
cat "$src" "$work/otto.cpio.gz" >"$out"

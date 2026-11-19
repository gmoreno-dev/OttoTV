#!/usr/bin/env bash
# shellcheck disable=SC2016  # the $ signs in this file are literal GRUB syntax
#
# Fixes the `linux` line of add_entry in upstream's /boot/grub/functions.cfg.
#
#  1. "$hwacccel" is a typo (the variable is $hwaccel), so the
#     "Disable Hardware Acceleration" toggle never reached the kernel.
#  2. Quoted, each option reaches the kernel as a single argument, so a
#     multi-word option such as "nomodeset HWACCEL=0" can't be passed.
#     Unquoted, GRUB splits them the same way it already does for
#     $KERNEL_ARGS. "$iso" stays quoted: it can hold a path with spaces.
#
# Usage: tools/patch-functions-cfg.sh <functions.cfg>
set -euo pipefail

file=$1
# ERE matching the literal text: linux "($root)${KERNEL}"
linux_line='linux "\(\$root\)\$\{KERNEL\}"'

grep -Eq "$linux_line" "$file" || {
	echo "patch-functions-cfg: add_entry's linux line not found; upstream changed?" >&2
	exit 1
}
grep -q '\$hwacccel' "$file" ||
	echo "patch-functions-cfg: note: \$hwacccel typo not found (fixed upstream?)" >&2

sed -i -E \
	-e "/$linux_line/ s/\"\\\$(2|pcmode|ffmpeg|hwacccel|hwaccel|setupwizard|copytoram)\"/\$\\1/g" \
	-e 's/\$hwacccel/$hwaccel/g' \
	"$file"

expected='linux "($root)${KERNEL}" $KERNEL_ARGS $2 $pcmode $ffmpeg $hwaccel $setupwizard $copytoram "$iso"'
grep -qF "$expected" "$file" || {
	echo "patch-functions-cfg: patch did not produce the expected line:" >&2
	grep -E "$linux_line" "$file" >&2
	exit 1
}

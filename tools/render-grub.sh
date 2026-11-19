#!/usr/bin/env bash
# Renders iso/boot/grub/grub.cfg.in into a real grub.cfg.
# Usage: tools/render-grub.sh <template> <output>
# Reads PRODUCT_NAME, VERSION, ISO_LABEL, BOOT_TIMEOUT and EXTRA_KERNEL_ARGS
# from the environment.
set -euo pipefail

template=$1
output=$2

for var in PRODUCT_NAME VERSION ISO_LABEL BOOT_TIMEOUT; do
	[ -n "${!var:-}" ] || { echo "render-grub: $var is not set" >&2; exit 1; }
done

# Escape sed replacement metacharacters (|, & and \) in the values.
esc() { printf '%s' "$1" | sed -e 's/[|&\\]/\\&/g'; }

sed -e "s|@PRODUCT_NAME@|$(esc "$PRODUCT_NAME")|g" \
	-e "s|@VERSION@|$(esc "$VERSION")|g" \
	-e "s|@ISO_LABEL@|$(esc "$ISO_LABEL")|g" \
	-e "s|@BOOT_TIMEOUT@|$(esc "$BOOT_TIMEOUT")|g" \
	-e "s|@EXTRA_KERNEL_ARGS@|$(esc "${EXTRA_KERNEL_ARGS:-}")|g" \
	"$template" >"$output"

if grep -n '@[A-Z_]*@' "$output" >&2; then
	echo "render-grub: unrendered placeholder left in $output" >&2
	exit 1
fi

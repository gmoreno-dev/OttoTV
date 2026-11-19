#!/usr/bin/env bash
# Builds Otto TV's own programs into a folder: otto-remote (Go), the TV
# app (app.ottotv.connect.apk) and libotto_movbe.so (native/movbe).
# build.sh calls it; on its own it makes the folder OTTO_PREBUILT=<dir>
# ./build.sh takes on a machine without Go or the Android SDK.
#
# Usage: tools/build-ours.sh <dir>
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/.." && pwd)
# shellcheck source=config.env
source "$ROOT/config.env"
OUTDIR=${1:?usage: tools/build-ours.sh <dir>}
mkdir -p "$OUTDIR"

# Static binary: Android's libc is not glibc, so nothing may be linked in.
(cd "$ROOT/remote" && CGO_ENABLED=0 GOOS=linux GOARCH=amd64 go build -trimpath \
	-ldflags "-s -w -X 'main.productName=$PRODUCT_NAME' -X main.version=$VERSION -X main.updateRepo=${UPDATE_REPO:-}" \
	-o "$OUTDIR/otto-remote" .)
chmod 755 "$OUTDIR/otto-remote"
"$ROOT/tools/build-tv-app.sh" "$OUTDIR/app.ottotv.connect.apk" "$VERSION" "$ROOT/cache/otto.keystore"
# No libc at all (it only makes raw syscalls), so a host compiler builds it
# for Android's linker as well.
cc -O2 -fPIC -shared -nostdlib -ffreestanding -fno-stack-protector -fno-builtin \
	-Wl,--hash-style=both -Wl,-z,noexecstack -Wl,-z,relro -Wl,-z,now -Wl,-soname,libotto_movbe.so \
	-o "$OUTDIR/libotto_movbe.so" "$ROOT/native/movbe/movbe.c"
if nm -D --undefined-only "$OUTDIR/libotto_movbe.so" | grep -q .; then
	echo "error: libotto_movbe.so needs symbols from elsewhere; it must stand alone" >&2
	exit 1
fi

#!/usr/bin/env bash
# Builds the Otto TV ISO by remastering the upstream LineageOS TV x86 ISO.
#
# The 2+ GB Android system image is reused untouched. We only replace the
# GRUB menu and add our boot hooks, boot arguments for installed systems,
# the phone remote and extra APKs, then write a new ISO with xorriso,
# replaying upstream's boot setup (BIOS + UEFI, USB-bootable).
#
# Usage: ./build.sh [path/to/upstream.iso]
# Needs: xorriso, curl, python3, sha256sum, Go, a JDK and the Android SDK
#   (Debian/Ubuntu/WSL: sudo apt install xorriso curl python3 golang
#    openjdk-17-jdk-headless zip, then tools/setup-android-sdk.sh)
# OTTO_PREBUILT=<dir> skips compiling: it takes otto-remote and
# app.ottotv.connect.apk from that folder instead.
set -euo pipefail

ROOT=$(cd "$(dirname "$0")" && pwd)
# shellcheck source=config.env
source "$ROOT/config.env"

CACHE="$ROOT/cache"
STAGE="$ROOT/build/stage"
OUT="$ROOT/out"

die() { echo "error: $*" >&2; exit 1; }
log() { echo "==> $*"; }

for tool in xorriso curl python3 sha256sum sed cpio gzip; do
	command -v "$tool" >/dev/null ||
		die "'$tool' not found (Debian/Ubuntu/WSL: sudo apt install xorriso curl python3)"
done
if [ -z "${OTTO_PREBUILT:-}" ]; then
	command -v go >/dev/null || die "'go' not found (Debian/Ubuntu/WSL: sudo apt install golang)"
fi

# 1. Upstream ISO -------------------------------------------------------------
UPSTREAM_ISO=${1:-$CACHE/upstream.iso}
if [ ! -f "$UPSTREAM_ISO" ]; then
	[ -n "$UPSTREAM_ISO_URL" ] || die "no upstream ISO: pass its path, or set UPSTREAM_ISO_URL in config.env"
	log "Downloading upstream ISO"
	mkdir -p "$CACHE"
	curl -fL --retry 3 -o "$UPSTREAM_ISO.part" "$UPSTREAM_ISO_URL"
	mv "$UPSTREAM_ISO.part" "$UPSTREAM_ISO"
fi

# OTTO_SKIP_HASH=1 accepts another upstream ISO: tests boot a trimmed copy
# (no Android system) in a virtual machine to check the boot menu.
if [ -n "$UPSTREAM_ISO_SHA256" ] && [ -z "${OTTO_SKIP_HASH:-}" ]; then
	log "Checking upstream ISO hash"
	echo "$UPSTREAM_ISO_SHA256  $UPSTREAM_ISO" | sha256sum -c --quiet - ||
		die "upstream ISO hash mismatch"
elif [ -n "${OTTO_SKIP_HASH:-}" ]; then
	echo "warning: OTTO_SKIP_HASH: not checking the upstream ISO" >&2
else
	echo "warning: UPSTREAM_ISO_SHA256 is empty. Pin this build in config.env:" >&2
	sha256sum "$UPSTREAM_ISO" >&2
fi

# 2. Volume label: ours (config.env). The initrd finds the boot media by the
# label in our menu's ROOT=LABEL=, and upstream's GRUB images find their
# config by the ISO's creation-time UUID, which xorriso keeps.
[ -n "${ISO_LABEL:-}" ] || die "ISO_LABEL is empty in config.env"

# 3. Stage our files ----------------------------------------------------------
log "Staging files"
rm -rf "$STAGE"
mkdir -p "$STAGE/grub" "$STAGE/otto/user_app" "$STAGE/otto/system_app/OttoConnect" \
	"$STAGE/mountpoint" "$CACHE/apks"
cp -r "$ROOT/iso/scripts" "$STAGE/scripts"
cp -r "$ROOT/iso/otto/." "$STAGE/otto/"
sed -i "s/@VERSION@/$VERSION/g" "$STAGE/otto/installer/branding/otto/branding.desc"
find "$STAGE" -name __pycache__ -prune -exec rm -rf {} +

# Installed systems don't use our GRUB menu: the upstream installer writes
# the first line of /cmdline.txt into their boot entries instead, so our
# defaults go there too.
# quiet, loglevel=3 and the hidden cursor: a clean screen with only Otto
# until the boot animation, as on the USB stick.
printf '%s\n' "quiet loglevel=3 vt.global_cursor_default=0 $EXTRA_KERNEL_ARGS SETUPWIZARD=0" >"$STAGE/cmdline.txt"

export PRODUCT_NAME VERSION ISO_LABEL BOOT_TIMEOUT EXTRA_KERNEL_ARGS
"$ROOT/tools/render-grub.sh" "$ROOT/iso/boot/grub/grub.cfg.in" "$STAGE/grub/grub.cfg"
# The boot menu's theme; the installer copies it for installed systems too.
cp -r "$ROOT/iso/boot/grub/themes/otto" "$STAGE/grub/theme"
mkdir -p "$STAGE/otto/installer"
cp -r "$ROOT/iso/boot/grub/themes/otto" "$STAGE/otto/installer/grub-theme"
# Installed menus have 3 items at most (no "Install"): the hints go up a row.
sed -i 's/top = 44%+236/top = 44%+184/' "$STAGE/otto/installer/grub-theme/theme.txt"
# Otto in text, for the installer's last screen (otto-restart).
cp "$ROOT/art/brand/otto-ascii.txt" "$STAGE/otto/installer/otto-ascii.txt"

# The initrd: upstream's, with a quiet boot screen showing Otto (see
# tools/patch-initrd.sh). Installed systems get it from the installer.
xorriso -osirrox on -indev "$UPSTREAM_ISO" -extract /initrd.img "$STAGE/initrd.upstream.img" 2>/dev/null ||
	die "could not extract /initrd.img from the upstream ISO"
"$ROOT/tools/patch-initrd.sh" "$STAGE/initrd.upstream.img" "$STAGE/initrd.img"

# 4. Phone remote: the service (Go) and the "Connect phone" TV app ------------
log "Building the phone remote"
mkdir -p "$STAGE/otto/remote"
REMOTE_BIN="$STAGE/otto/remote/otto-remote"
# A system app, not a user app: user apps are installed late in boot (and on
# every boot in live mode), and ours runs the pairing screen and the keyboard
# from the moment the home screen shows. See otto_system_apps in 9-otto.
CONNECT_APK="$STAGE/otto/system_app/OttoConnect/OttoConnect.apk"
if [ -n "${OTTO_PREBUILT:-}" ]; then
	cp "$OTTO_PREBUILT/otto-remote" "$REMOTE_BIN"
	cp "$OTTO_PREBUILT/app.ottotv.connect.apk" "$CONNECT_APK"
else
	# Static binary: Android's libc is not glibc, so nothing may be linked in.
	(cd "$ROOT/remote" && CGO_ENABLED=0 GOOS=linux GOARCH=amd64 go build -trimpath \
		-ldflags "-s -w -X 'main.productName=$PRODUCT_NAME' -X main.version=$VERSION" \
		-o "$REMOTE_BIN" .)
	"$ROOT/tools/build-tv-app.sh" "$CONNECT_APK" "$VERSION" "$CACHE/otto.keystore"
fi
chmod 755 "$REMOTE_BIN"

# 5. Apps from F-Droid (cached between builds) --------------------------------
# apps.lock records exactly which build of each app went into this ISO.
log "Fetching apps from F-Droid"
: >"$STAGE/otto/apps.lock"
# OTTO_NO_APPS=1 leaves them out (offline test builds).
while read -r source id; do
	[ -z "${OTTO_NO_APPS:-}" ] || break
	case "$source" in '' | '#'*) continue ;; esac
	[ "$source" = fdroid ] || die "apps.txt: unknown source '$source' (expected: fdroid)"

	read -r code url < <(python3 "$ROOT/tools/fdroid.py" resolve "$id") ||
		die "could not resolve '$id' on F-Droid"
	apk="$CACHE/apks/${id}_${code}.apk"
	if [ ! -f "$apk" ]; then
		curl -fL --retry 3 -o "$apk.part" "$url"
		mv "$apk.part" "$apk"
	fi

	abis=$(python3 "$ROOT/tools/fdroid.py" abis "$apk")
	case " $abis " in
	*" none "* | *" x86_64 "* | *" x86 "*) ;;
	*) echo "warning: $id only has native code for: $abis (will rely on ARM translation)" >&2 ;;
	esac

	cp "$apk" "$STAGE/otto/user_app/$id.apk"
	printf '%s %s %s\n' "$id" "$code" "$(sha256sum "$apk" | cut -d' ' -f1)" >>"$STAGE/otto/apps.lock"
	echo "    $id $code [$abis]"
done < <(tr -d '\r' <"$ROOT/apps.txt")
for built in "$REMOTE_BIN" "$CONNECT_APK"; do
	printf '%s %s %s\n' "$(basename "$built")" "$VERSION" "$(sha256sum "$built" | cut -d' ' -f1)" >>"$STAGE/otto/apps.lock"
done

# 6. Update archive for installed systems ---------------------------------------
# Our files alone: tools/device/update.sh swaps them in over adb, no
# reinstall. The bundled apps are installed already. OTTO_UPDATE_ONLY=1 stops
# here, which skips the minutes it takes to write the ISO while testing.
mkdir -p "$OUT"
UPDATE_TAR="$OUT/$ISO_BASENAME-$VERSION-update.tar"
tar -C "$STAGE" --format=ustar --owner=0 --group=0 --exclude=otto/user_app \
	-cf "$UPDATE_TAR" scripts otto initrd.img
log "Update archive: $UPDATE_TAR"
if [ -n "${OTTO_UPDATE_ONLY:-}" ]; then
	log "Done (OTTO_UPDATE_ONLY: no ISO)"
	exit 0
fi

# 7. Write the new ISO --------------------------------------------------------
# xorriso writes to a temporary file that only becomes the final ISO after it
# passes the checks below, so a failed build never leaves a broken ISO behind
# that looks finished.
mkdir -p "$OUT"
OUT_ISO="$OUT/$ISO_BASENAME-$VERSION.iso"
TMP_ISO="$OUT_ISO.part"
rm -f "$OUT_ISO" "$OUT_ISO.sha256" "$TMP_ISO"
trap 'rm -f "$TMP_ISO"' EXIT
log "Writing $OUT_ISO"
# /boot/otto is an empty folder that otto_expose_runtime mounts /otto on.
# (Mapped from an empty folder: -mkdir there makes xorriso drop the replayed
# boot setup.)
# -hfsplus off: upstream also carries an HFS+ copy of the file tree, for old
# Macs. libisofs 1.5.6 can't lay that out next to the EFI boot partition once
# files change ("Program error: GPT partitions 1 and 2 overlap") and aborts
# right at the start. Dropping it keeps BIOS (MBR) and UEFI (EFI partition)
# boot, which is what PCs use.
xorriso -indev "$UPSTREAM_ISO" -outdev "$TMP_ISO" \
	-volid "$ISO_LABEL" \
	-map "$STAGE/grub/grub.cfg" /boot/grub/grub.cfg \
	-map "$STAGE/grub/theme" /boot/grub/themes/otto \
	-rm_r /boot/grub/themes/blissos-white-1080p /boot/grub/functions.cfg -- \
	-map "$STAGE/cmdline.txt" /cmdline.txt \
	-map "$STAGE/initrd.img" /initrd.img \
	-map "$STAGE/scripts" /scripts \
	-map "$STAGE/otto" /otto \
	-map "$STAGE/mountpoint" /boot/otto \
	-boot_image any replay \
	-hfsplus off ||
	die "xorriso could not write the ISO (see its FAILURE messages above)"

log "Checking the new ISO"
new_label=$(xorriso -indev "$TMP_ISO" -pvd_info 2>/dev/null |
	sed -n 's/^Volume Id *: *//p' | head -n 1)
[ "$new_label" = "$ISO_LABEL" ] ||
	die "the new ISO is labeled '$new_label', expected '$ISO_LABEL'"
# Android only sees the ISO's /boot folder: otto_expose_runtime needs this.
xorriso -indev "$TMP_ISO" -ls /boot/otto >/dev/null 2>&1 ||
	die "the new ISO lacks the /boot/otto mount point"
boot_images=$(xorriso -indev "$TMP_ISO" -report_el_torito plain 2>/dev/null |
	grep -c '^El Torito boot img' || true)
[ "$boot_images" -ge 2 ] ||
	die "the new ISO lost its BIOS/UEFI boot images (found $boot_images, expected 2)"
mv "$TMP_ISO" "$OUT_ISO"
trap - EXIT

(cd "$OUT" && sha256sum "$(basename "$OUT_ISO")" >"$(basename "$OUT_ISO").sha256")
cp "$STAGE/otto/apps.lock" "$OUT/$ISO_BASENAME-$VERSION.apps.lock"
log "Done: $OUT_ISO"

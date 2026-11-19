#!/usr/bin/env bash
# Builds the "Connect phone" TV app (tvapp/) without Gradle: it is one
# activity, so aapt2, javac, d8 and apksigner are all it needs.
#
# Usage: tools/build-tv-app.sh <out.apk> <version, e.g. 0.3.0> <keystore>
# Needs: a JDK and the Android SDK (ANDROID_HOME) with build-tools and a
# platform. The keystore is created on first use; keep it to sign updates.
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/.." && pwd)
SRC="$ROOT/tvapp"
OUT_APK=$1 VERSION=$2 KEYSTORE=$3

die() { echo "error: $*" >&2; exit 1; }

SDK=${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}
[ -d "$SDK" ] || die "ANDROID_HOME is not set (tools/setup-android-sdk.sh installs the SDK)"
BT=$(find "$SDK/build-tools" -mindepth 1 -maxdepth 1 -type d 2>/dev/null | sort -V | tail -n 1)
PLATFORM=$(find "$SDK/platforms" -mindepth 1 -maxdepth 1 -type d 2>/dev/null | sort -V | tail -n 1)
[ -x "$BT/aapt2" ] && [ -f "$PLATFORM/android.jar" ] ||
	die "Android build-tools or platform missing in $SDK"
for tool in javac keytool zip; do
	command -v "$tool" >/dev/null || die "'$tool' not found (Debian/Ubuntu: sudo apt install openjdk-17-jdk-headless zip)"
done
JAR="$PLATFORM/android.jar"

# 0.3.0 -> 300, so newer releases always install over older ones.
IFS=. read -r major minor patch <<<"$VERSION"
CODE=$((major * 10000 + minor * 100 + ${patch%%[!0-9]*}))

WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT
mkdir -p "$WORK/gen" "$WORK/classes" "$WORK/dex"

"$BT/aapt2" compile --dir "$SRC/res" -o "$WORK/res.zip"
"$BT/aapt2" link -o "$WORK/app.apk" -I "$JAR" \
	--manifest "$SRC/AndroidManifest.xml" --java "$WORK/gen" \
	--min-sdk-version 28 --target-sdk-version 34 \
	--version-code "$CODE" --version-name "$VERSION" \
	"$WORK/res.zip"

find "$SRC/src" "$WORK/gen" -name '*.java' >"$WORK/sources"
javac -nowarn -source 8 -target 8 -bootclasspath "$JAR" -classpath "$JAR" \
	-d "$WORK/classes" @"$WORK/sources" 2>&1 | grep -v 'warning: \[options\]' || true
find "$WORK/classes" -name '*.class' >"$WORK/classlist"
[ -s "$WORK/classlist" ] || die "javac produced no classes"
"$BT/d8" --release --min-api 28 --lib "$JAR" --output "$WORK/dex" @"$WORK/classlist"
(cd "$WORK/dex" && zip -q "$WORK/app.apk" classes.dex)

if [ ! -f "$KEYSTORE" ]; then
	mkdir -p "$(dirname "$KEYSTORE")"
	keytool -genkeypair -keystore "$KEYSTORE" -alias tvbox -keyalg RSA -keysize 2048 \
		-validity 10000 -dname "CN=TV Box" -storepass tvboxapp -keypass tvboxapp >/dev/null 2>&1
fi
"$BT/zipalign" -f 4 "$WORK/app.apk" "$WORK/aligned.apk"
"$BT/apksigner" sign --ks "$KEYSTORE" --ks-pass pass:tvboxapp --ks-key-alias tvbox \
	--out "$OUT_APK" "$WORK/aligned.apk"
rm -f "$OUT_APK.idsig"
echo "built $OUT_APK ($VERSION, code $CODE)"

#!/usr/bin/env bash
# Installs the slice of the Android SDK that build.sh needs (one platform and
# one build-tools release, ~250 MB) into ~/android-sdk, or $ANDROID_HOME.
# Then: export ANDROID_HOME=~/android-sdk
set -euo pipefail

SDK=${ANDROID_HOME:-$HOME/android-sdk}
TOOLS_ZIP=https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip

command -v java >/dev/null || { echo "error: install a JDK first (sudo apt install openjdk-17-jdk-headless)" >&2; exit 1; }
mkdir -p "$SDK/cmdline-tools"
if [ ! -x "$SDK/cmdline-tools/latest/bin/sdkmanager" ]; then
	tmp=$(mktemp -d)
	trap 'rm -rf "$tmp"' EXIT
	curl -fL --retry 3 -o "$tmp/tools.zip" "$TOOLS_ZIP"
	unzip -q "$tmp/tools.zip" -d "$tmp"
	rm -rf "$SDK/cmdline-tools/latest"
	mv "$tmp/cmdline-tools" "$SDK/cmdline-tools/latest"
fi
yes | "$SDK/cmdline-tools/latest/bin/sdkmanager" --sdk_root="$SDK" \
	"platforms;android-34" "build-tools;34.0.0" >/dev/null
echo "Android SDK ready in $SDK. Add this to your shell profile:"
echo "  export ANDROID_HOME=$SDK"

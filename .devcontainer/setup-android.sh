#!/usr/bin/env bash
set -euo pipefail

SDK_ROOT="$HOME/android-sdk"
CMDLINE_TOOLS_URL="https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip"
GRADLE_VERSION="9.4.1"

mkdir -p "$SDK_ROOT/cmdline-tools"
cd /tmp
curl -fsSLo cmdline-tools.zip "$CMDLINE_TOOLS_URL"
unzip -q -o cmdline-tools.zip -d "$SDK_ROOT/cmdline-tools"
rm -rf "$SDK_ROOT/cmdline-tools/latest"
mv "$SDK_ROOT/cmdline-tools/cmdline-tools" "$SDK_ROOT/cmdline-tools/latest"

SDKMANAGER="$SDK_ROOT/cmdline-tools/latest/bin/sdkmanager"
yes | "$SDKMANAGER" --sdk_root="$SDK_ROOT" --licenses >/dev/null
"$SDKMANAGER" --sdk_root="$SDK_ROOT" "platform-tools" "platforms;android-37" "build-tools;37.0.0"

cd /tmp
curl -fsSLo gradle.zip "https://services.gradle.org/distributions/gradle-${GRADLE_VERSION}-bin.zip"
sudo rm -rf "/opt/gradle-${GRADLE_VERSION}"
sudo unzip -q -o gradle.zip -d /opt
sudo ln -sf "/opt/gradle-${GRADLE_VERSION}/bin/gradle" /usr/local/bin/gradle

rm -f /tmp/cmdline-tools.zip /tmp/gradle.zip

#!/usr/bin/env bash
# Build the tether helper dex. Requires: JDK 17+, Android SDK build-tools + platform.
set -e
cd "$(dirname "$0")"

SDK="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$HOME/Library/Android/sdk}}"
[ -d "$SDK/platforms" ] || { echo "Android SDK not found at '$SDK'. Set ANDROID_SDK_ROOT."; exit 1; }
BT="$(ls -d "$SDK"/build-tools/*/ 2>/dev/null | sort -V | tail -1)"
AJAR="$(ls "$SDK"/platforms/*/android.jar 2>/dev/null | sort -V | tail -1)"
JAVAC="${JAVA_HOME:+$JAVA_HOME/bin/}javac"

rm -f *.class classes.dex
"$JAVAC" --release 17 -classpath "$AJAR" -d . Main.java
"${BT}d8" --min-api 30 --output . *.class
echo "Built classes.dex"
echo "Install on device with:  adb push classes.dex /data/local/tmp/tether.dex"

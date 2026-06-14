#!/usr/bin/env bash
# Build & sign the Auto Tether app (no Gradle). Requires: JDK 17+, Android SDK
# (build-tools + a platform). Set ANDROID_SDK_ROOT if it isn't auto-detected.
set -e
cd "$(dirname "$0")"

SDK="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$HOME/Library/Android/sdk}}"
[ -d "$SDK/platforms" ] || { echo "Android SDK not found at '$SDK'. Set ANDROID_SDK_ROOT."; exit 1; }
BT="$(ls -d "$SDK"/build-tools/*/ 2>/dev/null | sort -V | tail -1)"
AJAR="$(ls "$SDK"/platforms/*/android.jar 2>/dev/null | sort -V | tail -1)"
[ -n "$BT" ] && [ -n "$AJAR" ] || { echo "Need Android build-tools and a platform (android.jar)."; exit 1; }
JAVAC="${JAVA_HOME:+$JAVA_HOME/bin/}javac"
KEYTOOL="${JAVA_HOME:+$JAVA_HOME/bin/}keytool"

# adb library (Tananaev adblib, fetched from JitPack)
ADBLIB="libs/adblib.jar"
[ -f "$ADBLIB" ] || { mkdir -p libs; curl -fsSL -o "$ADBLIB" \
  https://jitpack.io/com/github/tananaev/adblib/master-SNAPSHOT/adblib-master-SNAPSHOT.jar; }

# local debug keystore (generated once, never committed)
[ -f debug.keystore ] || "$KEYTOOL" -genkeypair -dname "CN=AutoTether" -alias t \
  -keyalg RSA -keysize 2048 -validity 10000 -keystore debug.keystore \
  -storepass android -keypass android >/dev/null 2>&1

rm -rf out && mkdir -p out
"${BT}aapt2" link -o out/base.apk -I "$AJAR" --manifest AndroidManifest.xml \
  --min-sdk-version 30 --target-sdk-version 35
"$JAVAC" --release 17 -classpath "$ADBLIB:$AJAR" -d out $(find src -name '*.java')
"${BT}d8" --min-api 30 --output out $(find out -name '*.class') "$ADBLIB"
( cd out && zip -q base.apk classes.dex )
"${BT}zipalign" -f -p 4 out/base.apk out/aligned.apk
"${BT}apksigner" sign --ks debug.keystore --ks-pass pass:android --key-pass pass:android \
  --out out/autotether.apk out/aligned.apk
echo "Built: $(pwd)/out/autotether.apk"

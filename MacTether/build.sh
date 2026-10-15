#!/usr/bin/env bash
# Build the MacTether menu-bar app. Requires Xcode command-line tools.
set -e
cd "$(dirname "$0")"

APP="MacTether.app"
rm -rf "$APP"
mkdir -p "$APP/Contents/MacOS" "$APP/Contents/Resources"

swiftc -O -framework AppKit -framework Foundation \
  -o "$APP/Contents/MacOS/MacTether" src/main.swift

# Resolve the helper dex (all copies are git-ignored build outputs):
# bundled copy → android app assets → helper build → build it from source.
DEX=""
for c in res/tether.dex ../app/src/main/assets/tether.dex ../helper/classes.dex; do
  [ -f "$c" ] && DEX="$c" && break
done
if [ -z "$DEX" ]; then
  echo "Building the helper dex from source…"
  (cd ../helper && ./build.sh)
  DEX="../helper/classes.dex"
fi
cp "$DEX" "$APP/Contents/Resources/tether.dex"
cp "$DEX" res/tether.dex 2>/dev/null || true
cp res/*.png "$APP/Contents/Resources/" 2>/dev/null || true
cp Info.plist "$APP/Contents/"

codesign --force --deep --sign - "$APP" 2>/dev/null || true

echo "Built $APP — open it with: open $APP"

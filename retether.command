#!/usr/bin/env bash
# Auto Tether — run this after a phone REBOOT.
# The app talks to the phone's own adb over 127.0.0.1:5555. That port resets on
# reboot (making it survive needs root), so re-enable it once: plug the phone into
# this computer via USB and double-click this file (macOS) or run it.

ADB="$(command -v adb 2>/dev/null)"
if [ -z "$ADB" ]; then
  for p in /opt/homebrew/bin/adb /usr/local/bin/adb "$HOME/Library/Android/sdk/platform-tools/adb" "$ANDROID_SDK_ROOT/platform-tools/adb"; do
    [ -x "$p" ] && ADB="$p" && break
  done
fi
[ -z "$ADB" ] && { echo "adb not found. Install Android platform-tools."; exit 1; }

echo "──────────────────────────────────────────"
echo " Auto Tether: re-enabling after reboot"
echo "──────────────────────────────────────────"
echo "Plug the phone into this computer via USB now (if not already)."
echo "Waiting for the phone…"
"$ADB" wait-for-device

# Approve any 'Allow USB debugging?' prompt on the phone, then it continues.
"$ADB" tcpip 5555 >/dev/null 2>&1
sleep 3
"$ADB" wait-for-device 2>/dev/null

if "$ADB" shell ss -tlnH 2>/dev/null | grep -q ':5555'; then
  echo ""
  echo "✅ Done. Unplug USB and connect the adapter — tethering will turn on by itself."
else
  echo ""
  echo "⚠️  Could not confirm. Unlock the phone, approve the debugging prompt, and run again."
fi
echo ""
echo "(You can close this window.)"

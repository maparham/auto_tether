#!/usr/bin/env bash
# Auto Tether — turn tethering on directly from this computer.
#
# A simpler alternative to the on-phone app: no pairing, no Wireless Debugging.
# The phone is already reachable over USB adb, so this just runs the tether helper
# on it. Plug the phone into this computer via USB, then double-click this file
# (macOS) or run it.
#
# Usage:
#   ./tether.command          # Ethernet tethering (USB-Ethernet adapter on the phone)
#   ./tether.command usb      # USB tethering (phone shares net with this computer)
#   ./tether.command off      # turn the most recent tethering off
#
# Note: for Ethernet tethering, connect over Wi-Fi adb (run `adb tcpip 5555` once,
# then `adb connect <phone-ip>:5555`) so the USB port is free for the adapter — or
# use a USB hub. For USB tethering, plug straight into this computer.

set -e
cd "$(dirname "$0")"

DEX_LOCAL="helper/classes.dex"
DEX_REMOTE="/data/local/tmp/tether.dex"

# tethering types understood by the helper
TYPE_ETHERNET=5
TYPE_USB=1

# ── locate adb ───────────────────────────────────────────────────────────────
ADB="$(command -v adb 2>/dev/null)"
if [ -z "$ADB" ]; then
  for p in /opt/homebrew/bin/adb /usr/local/bin/adb "$HOME/Library/Android/sdk/platform-tools/adb" "$ANDROID_SDK_ROOT/platform-tools/adb"; do
    [ -x "$p" ] && ADB="$p" && break
  done
fi
[ -z "$ADB" ] && { echo "adb not found. Install Android platform-tools."; exit 1; }

# ── pick action ──────────────────────────────────────────────────────────────
ACTION="start"
TYPE=$TYPE_ETHERNET
LABEL="Ethernet"
case "${1:-}" in
  ""|eth|ethernet) ;;
  usb)             TYPE=$TYPE_USB; LABEL="USB" ;;
  off|stop)        ACTION="stop" ;;
  *) echo "Unknown option '$1'. Use: (nothing) | usb | off"; exit 1 ;;
esac

echo "──────────────────────────────────────────"
echo " Auto Tether: ${LABEL} tethering — ${ACTION}"
echo "──────────────────────────────────────────"
echo "Waiting for the phone over adb…"
"$ADB" wait-for-device

# ── make sure the helper is on the phone ─────────────────────────────────────
if ! "$ADB" shell "[ -f $DEX_REMOTE ]" 2>/dev/null; then
  if [ ! -f "$DEX_LOCAL" ]; then
    echo "Building the helper…"
    (cd helper && ./build.sh)
  fi
  echo "Pushing the helper to the phone…"
  "$ADB" push "$DEX_LOCAL" "$DEX_REMOTE" >/dev/null
fi

# ── run it (as the shell user, which holds the tethering privilege) ──────────
OUT="$("$ADB" shell "CLASSPATH=$DEX_REMOTE app_process /system/bin Main $TYPE $ACTION" 2>&1)"
echo "$OUT"

echo ""
if echo "$OUT" | grep -q "STARTED"; then
  echo "✅ ${LABEL} tethering is ON."
elif [ "$ACTION" = "stop" ]; then
  echo "✅ Tethering stopped."
else
  echo "⚠️  Could not confirm it turned on. Make sure the adapter/cable is connected"
  echo "    and the phone is unlocked, then run again."
fi
echo ""
echo "(You can close this window.)"

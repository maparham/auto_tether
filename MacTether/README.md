# MacTether — menu-bar toggle for USB / LAN tethering

Lives in the Mac menu bar. No pairing or Wireless debugging needed
(the phone is reachable over USB adb, same trick as `../tether.command`).

## Build & run

```sh
./build.sh   # produces MacTether.app
open MacTether.app
```

Menu:

- **Turn USB Tethering On / Off** — phone shares its connection with this Mac.
- **Turn LAN Tethering On / Off** — phone shares via a USB-Ethernet adapter.
  Checkmarks track the phone's actual state (refreshed every 10s and after
  every action; dashed while the phone is unreachable).
- **Auto-enable USB on reconnect** (on by default) — when the phone
  reappears over adb, USB tethering is turned on automatically. Only fires
  on absent→present transitions: nothing happens at launch if the phone is
  already connected, and a manual Off while connected is never overridden.
- **Quit**.

Uses the `adb` on your PATH (`brew install android-platform-tools` if missing).

## Notes

- The phone needs USB debugging enabled and must be plugged in via USB.
- LAN tethering needs the adapter on the phone's USB port, so over USB adb
  it requires a hub (or Wi-Fi adb); otherwise the phone is unreachable.
- No Wireless-debugging pairing is ever required from this app.
- Headless use: `MacTether.app/Contents/MacOS/MacTether --on | --off | --lan-on | --lan-off` (`--print-states` prints `usb=on|off lan=on|off` for debugging).
- The helper dex is resolved at build time (`res/` → app assets → helper
  build) and bundled; it is pushed to the phone if missing.
- Watcher activity is logged to `~/Library/Caches/mactether-watch.log`.

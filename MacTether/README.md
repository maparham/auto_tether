# MacTether — menu-bar toggle for USB / LAN tethering

Lives in the Mac menu bar. No pairing or Wireless debugging needed
(the phone is reachable over USB adb, same trick as `../tether.command`).

## Build & run

```sh
./build.sh   # produces MacTether.app
open MacTether.app
```

Menu:

- **USB Tethering** — phone shares its connection with this Mac.
- **LAN Tethering** — phone shares via a USB-Ethernet adapter.
- **Hotspot** — Wi-Fi hotspot (tethering type 0 via the helper;
  detected as the SoftAP interface `wlan1`/`ap0`/`swlan0` in TetheredState).
- **Wi-Fi** — the phone's own Wi-Fi, via `svc wifi` (no helper
  needed; state read from `settings get global wifi_on`).

Each is a single toggle: the checkmark shows the phone's actual state
(refreshed every 10s and after every action; dashed while the phone is
unreachable) and clicking flips it.
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
- Headless use: `MacTether.app/Contents/MacOS/MacTether --on | --off | --lan-on | --lan-off | --hotspot-on | --hotspot-off | --wifi-on | --wifi-off` (`--print-states` prints `usb=… lan=… hotspot=… wifi=…` for debugging).
- The helper dex is resolved at build time (`res/` → app assets → helper
  build) and bundled; it is pushed to the phone if missing.
- Watcher activity is logged to `~/Library/Caches/mactether-watch.log`.

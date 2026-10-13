# MacTether — menu-bar toggle for USB tethering

Lives in the Mac menu bar. Two actions, no pairing or Wireless debugging needed
(the phone is reachable over USB adb, same trick as `../tether.command`).

## Build & run

```sh
./build.sh   # produces MacTether.app
open MacTether.app
```

Click the tray icon → **Turn USB Tethering On** / **Turn USB Tethering Off**.
Uses the `adb` on your PATH (`brew install android-platform-tools` if missing).

## Notes

- The phone needs USB debugging enabled and must be plugged in via USB.
- No Wireless-debugging pairing is ever required from this app.
- Headless use: `MacTether.app/Contents/MacOS/MacTether --on | --off`.
- The helper dex is bundled (`res/tether.dex`) and pushed to the phone if missing.

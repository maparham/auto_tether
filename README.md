# Auto Tether

Automatically turns on **Ethernet tethering** (USB‑Ethernet adapter) and **USB tethering**
on an Android phone — **no root** — so you never tap the toggle yourself.

- Plug a **USB‑Ethernet adapter** into the phone → Ethernet tethering turns on.
- Plug the phone into a **computer** over USB → USB tethering turns on.

Built and tested on a Pixel 8a. Should work on other modern Pixels / stock Android.

## How it works

Stock Android won't let a normal app enable tethering — it needs a privileged
permission (`TETHER_PRIVILEGED` / `NETWORK_SETTINGS`) that can't be granted to apps.
The trick:

1. **`helper/`** — a tiny `app_process` program that calls the hidden
   `TetheringManager.startTethering(...)` API. Run as the **shell** user it *does*
   hold the needed permission. (It presents itself as `com.android.shell` via an
   `AttributionSource` so the tethering service accepts the caller.)
2. **`app/`** — a foreground service that watches for the adapter / USB connection,
   then runs that helper **as shell** by speaking adb to the phone's *own* adb daemon
   on `127.0.0.1:5555` (via [tananaev/adblib](https://github.com/tananaev/adblib)).
   No Wi‑Fi and no second device are involved — the phone talks to itself over loopback.

The only catch (a no‑root limitation): the `adb tcpip 5555` foothold resets on reboot,
so after a restart you re‑enable it once with `retether.command` (or `adb tcpip 5555`).
Making it survive reboots would require root.

## Requirements

- A phone with **USB debugging** enabled.
- A computer with **adb**, the **Android SDK** (build‑tools + one platform), and **JDK 17+**.
- Set `ANDROID_SDK_ROOT` if the SDK isn't at `~/Library/Android/sdk`.

## Setup

```sh
# 1. Build the helper and push it to the phone
cd helper && ./build.sh
adb push classes.dex /data/local/tmp/tether.dex

# 2. Enable adb-over-TCP on the phone (the app's foothold)
adb tcpip 5555

# 3. Build and install the app
cd ../app && ./build.sh
adb install -r out/autotether.apk
```

Then on the phone: open **Auto Tether** once, approve the **"Allow USB debugging?"**
prompt (check *Always allow*), and allow notifications. The background watcher now runs.

Plug in the adapter (or a USB cable to a computer) and tethering turns on by itself.

### After a reboot

Port 5555 is gone after a restart. Plug the phone into the computer and run
`retether.command` (double‑click on macOS) — it runs `adb tcpip 5555` and you're set
again until the next reboot.

## Layout

```
app/      foreground watcher app (Java, no Gradle — build.sh uses aapt2/d8/apksigner)
helper/   the app_process tethering helper (single Java file -> dex)
retether.command   re-enable the adb foothold after a reboot
```

## Credits

- [tananaev/adblib](https://github.com/tananaev/adblib) — pure‑Java adb client.

## Disclaimer

Uses non‑public Android APIs and adb‑over‑loopback. Provided as‑is, for personal use.

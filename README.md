# Auto Tether

Automatically turns on **Ethernet tethering** and **USB tethering** on an Android
phone — **no root** — so you never have to tap the toggle yourself.

- Plug a **USB‑Ethernet adapter** into the phone → Ethernet tethering turns on.
- Plug the phone into a **computer** over USB → USB tethering turns on.

Built and tested on a Pixel 8a. Should work on other modern Pixels / stock Android.

## How it works

Stock Android won't let an ordinary app turn on tethering — it's a privileged action
reserved for the system. Auto Tether works around this without root by having the
phone connect to itself the same way a computer would over a USB‑debugging connection,
which lets it flip the tethering switch with the elevated access that connection
provides.

A background watcher notices when an adapter or computer is plugged in and turns the
right kind of tethering on by itself. Everything happens on the phone — no Wi‑Fi and
no second device are needed once it's set up.

The one catch (a no‑root limitation): the self‑connection foothold resets every time
the phone reboots, so after a restart you re‑enable it once. Making it survive reboots
would require root.

## Requirements

- A phone with **USB debugging** enabled.
- A computer set up to build and install the app onto the phone, with **JDK 17+**.

## Setup

1. Build and install the app onto the phone.
2. Open **Auto Tether** once and follow the on‑screen steps — it guides you through
   granting access and pairing, mostly automatically.

After that, plug in the adapter (or a USB cable to a computer) and tethering turns on
by itself. Wi‑Fi can be off and you can close the app.

### After a reboot

The self‑connection foothold is gone after a restart. Plug the phone into the computer
and run `retether.command` (double‑click on macOS) to re‑enable it — you're set again
until the next reboot.

## Disclaimer

Relies on non‑public Android behavior. Provided as‑is, for personal use.

package io.github.maparham.autotether;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.hardware.usb.UsbManager;
import android.os.IBinder;
import android.util.Log;

import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Foreground service: watches for the USB-Ethernet adapter appearing and auto-runs the
 * tether helper, so the user never has to open the app.
 */
public class TetherService extends Service {
    static final String CH = "ethtether";
    static final String PREFS = "autotether";
    // User-pinned adapter interface name (set from the in-app picker when auto-detection fails).
    // Empty/absent means auto-detect.
    static final String KEY_IFACE = "ifaceOverride";
    // Set by the watcher when a USB host device is attached but no interface could be auto-identified;
    // MainActivity reads it on resume to pop the interface picker.
    static final String KEY_NEEDS_PICK = "needsIfacePick";
    // Interface-name prefixes we never treat as an Ethernet adapter: loopback, Wi-Fi, cellular,
    // virtual/tunnel pseudo-devices, and the phone's OWN usb-tether gadget (ncm/rndis) — those are
    // device-mode interfaces, not an attached adapter. Anything else with a MAC that is up is a
    // candidate, so the trigger adapts to eth0 / usb0 / enxAABBCC… without a hardcoded pattern.
    static final String[] INTERNAL_PREFIXES = {
        "lo", "wlan", "p2p", "aware", "nan", "rmnet", "ccmni", "rmnet_data", "umts",
        "dummy", "sit", "gre", "gretap", "erspan", "tunl", "tun", "ip6tnl", "ip_vti",
        "ip6gre", "ifb", "radiotap", "bond", "ncm", "rndis"
    };
    // The thread currently designated to run the poll loop. A loop iteration runs only while it is
    // still the designated thread (identity check), so designating a new one cleanly retires any old
    // or wedged thread. Cleared in onDestroy to stop the loop entirely.
    volatile Thread loopThread;
    // elapsedRealtime of the loop's last iteration — a heartbeat used to detect a wedged loop.
    volatile long lastTick = 0;
    // If the loop hasn't ticked in this long, treat it as dead/wedged and start a fresh one. Must
    // comfortably exceed a worst-case iteration (a couple of bounded runCmd calls back-to-back).
    static final long STALE_MS = 60000;
    // How many consecutive polls an interface must be present before we tether it, so the
    // interface (and EthernetManager) has settled — tethering a half-up eth0 fails with ENODEV.
    static final int STABLE_POLLS = 3; // ~7.5s at the 2.5s poll interval
    final Map<String, Integer> presentCount = new HashMap<>();
    final Set<String> tethered = new HashSet<>();
    boolean usbTetherDone = false;
    String lastUsbSig = "";
    String status = "watching for adapter…";
    // Consecutive polls where a USB host device is attached but no interface matched — used to decide
    // when auto-detection has "failed" and we should ask the user to pick the interface.
    int unidentifiedPolls = 0;
    boolean pickPrompted = false;
    // While an Ethernet adapter is present (or was, very recently), suppress USB tethering — the two
    // share the one USB-C port, and a USB-tether request reconfigures it and kills a live Ethernet
    // session. Refreshed every poll an adapter is seen, so a brief eth0 flicker during tether setup
    // (when Android cycles the interface into its bridge) doesn't open a window for USB to stomp it.
    volatile long ethCooldownUntil = 0;
    static final long ETH_COOLDOWN_MS = 25000;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground(1, buildNotification(status));
        // Every wake trigger (boot / power-connected / adapter-attached) lands here. Revive the loop
        // if it died or wedged — the old static-`running` guard could never restart a hung watcher,
        // so an adapter attached after a hang was silently ignored.
        ensureLoopAlive();
        return START_STICKY;
    }

    synchronized void ensureLoopAlive() {
        Thread t = loopThread;
        long now = android.os.SystemClock.elapsedRealtime();
        boolean healthy = t != null && t.isAlive() && (now - lastTick) < STALE_MS;
        if (healthy) return;
        Log.i("AutoTether", "watcher not healthy (alive=" + (t != null && t.isAlive())
                + ", sinceTick=" + (lastTick == 0 ? "never" : (now - lastTick) + "ms") + ") → (re)starting loop");
        lastTick = now; // grace period so a long first iteration doesn't immediately re-trigger
        Thread nt = new Thread(this::loop);
        loopThread = nt; // designating nt retires any prior thread (loop exits on identity mismatch)
        nt.start();
    }

    void loop() {
        Thread self = Thread.currentThread();
        while (loopThread == self) {
            lastTick = android.os.SystemClock.elapsedRealtime();
            try {
                // keep the adb connection alive while idle, so it's ready (and pinned to Wi-Fi)
                // before the adapter appears and shifts network routing
                AdbRunner.maintainConnection(this);

                Set<String> now = currentTargets();
                // Tether an adapter only once it's been present for a few polls — firing the
                // instant eth0 appears races interface setup and fails ("No such device").
                for (String iface : now) {
                    int c = (presentCount.containsKey(iface) ? presentCount.get(iface) : 0) + 1;
                    presentCount.put(iface, c);
                    if (c == STABLE_POLLS && !tethered.contains(iface)) {
                        tethered.add(iface);
                        Log.i("AutoTether", "adapter stable: " + iface + " → tethering");
                        update("adapter " + iface + " connected, enabling…");
                        try {
                            String r = AdbRunner.tether(this);
                            update(r.contains("STARTED") ? "tethering ON (" + iface + ")" : "result: " + r);
                        } catch (Exception e) {
                            update("error: " + e);
                            Log.e("AutoTether", "tether failed", e);
                        }
                    }
                }
                // Forget interfaces that went away, so a re-plug re-arms the stabilize-then-tether cycle.
                presentCount.keySet().retainAll(now);
                tethered.retainAll(now);
                // Keep the Ethernet cooldown fresh while any adapter is visible.
                if (!now.isEmpty()) ethCooldownUntil = android.os.SystemClock.elapsedRealtime() + ETH_COOLDOWN_MS;

                // USB tethering: phone plugged into a computer (peripheral). Must fire even when the
                // screen is locked (charge-only). We can't rely on USB "configured" (false while
                // locked), so trigger on USB data-connected OR USB power present (both survive lock;
                // host/Ethernet mode draws no USB power, so it won't false-fire there).
                android.content.Intent ust = registerReceiver(null,
                        new android.content.IntentFilter("android.hardware.usb.action.USB_STATE"));
                boolean connd = ust != null && ust.getBooleanExtra("connected", false);
                boolean confd = ust != null && ust.getBooleanExtra("configured", false);
                boolean battUsb = isUsbPowered();
                String sig = "conn=" + connd + " conf=" + confd + " battUSB=" + battUsb;
                if (!sig.equals(lastUsbSig)) { Log.i("AutoTether", "USB signals " + sig); lastUsbSig = sig; }
                boolean usbPresent = connd || battUsb;
                // Don't USB-tether while an Ethernet adapter is attached or was just tethered: an
                // adapter present as a USB host device, or a fresh Ethernet session within the
                // cooldown, both mean a USB-tether request would reconfigure the shared port and
                // break Ethernet (the "Ethernet started then dropped" failure).
                boolean ethActive = android.os.SystemClock.elapsedRealtime() < ethCooldownUntil;
                boolean adapterAttached = usbHostDevicePresent();
                if (usbPresent && now.isEmpty() && !ethActive && !adapterAttached) {
                    if (!usbTetherDone) {
                        Log.i("AutoTether", "USB host present (" + sig + ") → enabling USB tethering");
                        update("USB connected, enabling USB tethering…");
                        try {
                            String r = AdbRunner.usbTether(this);
                            // STARTED = we enabled it; error=18 (DUPLICATE_REQUEST) = already on.
                            if (r.contains("STARTED") || r.contains("error=18")) { usbTetherDone = true; update("USB tethering ON"); }
                            else update("USB result: " + r);
                        } catch (Exception e) { update("USB error: " + e); Log.e("AutoTether", "usb tether failed", e); }
                    }
                } else {
                    usbTetherDone = false;
                }

                // Auto-detection failure: a USB host device is attached (we're the host, not a
                // peripheral plugged into a computer) yet no interface matched. Give the interface a
                // few polls to come up; if it never does, ask the user to pick it from a list.
                boolean hostAdapter = usbHostDevicePresent() && !connd;
                if (hostAdapter && now.isEmpty() && override() == null) {
                    if (++unidentifiedPolls >= STABLE_POLLS && !pickPrompted) {
                        pickPrompted = true;
                        Log.w("AutoTether", "USB device attached but no interface auto-identified → asking user to pick");
                        prompts().edit().putBoolean(KEY_NEEDS_PICK, true).apply();
                        postPickNotification();
                        update("adapter attached but its network interface wasn't recognized — tap to pick it");
                    }
                } else {
                    unidentifiedPolls = 0;
                    if (!hostAdapter && pickPrompted) { // adapter unplugged — clear the prompt
                        pickPrompted = false;
                        prompts().edit().putBoolean(KEY_NEEDS_PICK, false).apply();
                        NotificationManager nm = getSystemService(NotificationManager.class);
                        if (nm != null) nm.cancel(2);
                    }
                }

                if (now.isEmpty() && !usbTetherDone && !pickPrompted) update("watching for adapter…");
            } catch (Throwable t) {
                Log.e("AutoTether", "loop error", t);
            }
            try { Thread.sleep(2500); } catch (InterruptedException e) { break; }
        }
    }

    /**
     * The interfaces that should trigger Ethernet tethering. If the user pinned one (auto-detection
     * failed before), trust only that. Otherwise discover dynamically: any up, MAC-bearing interface
     * that isn't a known-internal one (Wi-Fi / cellular / tunnel / our own usb-tether gadget).
     */
    Set<String> currentTargets() throws Exception {
        String pinned = override();
        Set<String> s = new HashSet<>();
        Enumeration<NetworkInterface> e = NetworkInterface.getNetworkInterfaces();
        while (e != null && e.hasMoreElements()) {
            NetworkInterface ni = e.nextElement();
            if (pinned != null) {
                if (pinned.equals(ni.getName()) && ni.isUp()) s.add(ni.getName());
            } else if (isEthernetCandidate(ni)) {
                s.add(ni.getName());
            }
        }
        return s;
    }

    /** Heuristic: an up, non-internal interface with a hardware MAC looks like an attached adapter. */
    static boolean isEthernetCandidate(NetworkInterface ni) {
        try {
            if (!ni.isUp() || ni.isLoopback() || ni.isVirtual()) return false;
            if (ni.getHardwareAddress() == null) return false; // cellular/p2p links have no MAC
            return !isInternalName(ni.getName());
        } catch (Throwable t) { return false; }
    }

    static boolean isInternalName(String name) {
        for (String p : INTERNAL_PREFIXES) if (name.startsWith(p)) return true;
        return false;
    }

    String override() {
        String v = prefs().getString(KEY_IFACE, null);
        return (v == null || v.trim().isEmpty()) ? null : v.trim();
    }

    SharedPreferences prefs() { return getSharedPreferences(PREFS, MODE_PRIVATE); }
    SharedPreferences prompts() { return prefs(); }

    /** True when at least one USB device is attached with the phone acting as host (i.e. an adapter,
     *  hub, etc.) — distinct from the phone being a peripheral plugged into a computer. */
    boolean usbHostDevicePresent() {
        try {
            UsbManager um = getSystemService(UsbManager.class);
            return um != null && !um.getDeviceList().isEmpty();
        } catch (Throwable t) { return false; }
    }

    /**
     * Interfaces to offer in the picker: everything that isn't obviously internal, including ones
     * that are down or have no IP yet, so the user can pin an oddly-named adapter (e.g. ncm0/rndis0).
     * Returns {name, "up · MAC aa:bb:…"} pairs.
     */
    public static List<String[]> selectableInterfaces() {
        List<String[]> out = new ArrayList<>();
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                String name = ni.getName();
                // For the picker, keep loopback/Wi-Fi/cellular/tunnels out but DO show ncm/rndis so a
                // user whose adapter enumerates that way can still pin it.
                if (name.startsWith("lo") || name.startsWith("wlan") || name.startsWith("p2p")
                        || name.startsWith("rmnet") || name.startsWith("ccmni") || name.startsWith("dummy")
                        || name.startsWith("sit") || name.startsWith("gre") || name.startsWith("tunl")
                        || name.startsWith("tun") || name.startsWith("ip6") || name.startsWith("ip_vti")
                        || name.startsWith("erspan") || name.startsWith("ifb") || name.startsWith("radiotap")
                        || name.startsWith("aware") || name.startsWith("nan") || name.startsWith("umts")) continue;
                StringBuilder detail = new StringBuilder();
                try { detail.append(ni.isUp() ? "up" : "down"); } catch (Throwable t) { detail.append("?"); }
                try {
                    byte[] mac = ni.getHardwareAddress();
                    if (mac != null) {
                        StringBuilder h = new StringBuilder();
                        for (byte x : mac) h.append(String.format("%02x:", x));
                        if (h.length() > 0) h.setLength(h.length() - 1);
                        detail.append(" · ").append(h);
                    }
                } catch (Throwable ignore) {}
                out.add(new String[]{name, detail.toString()});
            }
        } catch (Throwable ignore) {}
        return out;
    }

    /** Persist (or clear, when name is null) the user's interface choice and clear the pick prompt. */
    public static void setInterfaceOverride(Context ctx, String name) {
        SharedPreferences p = ctx.getSharedPreferences(PREFS, MODE_PRIVATE);
        SharedPreferences.Editor ed = p.edit();
        if (name == null) ed.remove(KEY_IFACE); else ed.putString(KEY_IFACE, name);
        ed.putBoolean(KEY_NEEDS_PICK, false).apply();
        NotificationManager nm = ctx.getSystemService(NotificationManager.class);
        if (nm != null) nm.cancel(2);
    }

    /** Notification that taps through to the in-app interface picker. */
    void postPickNotification() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm == null) return;
        if (nm.getNotificationChannel(CH) == null) {
            nm.createNotificationChannel(new NotificationChannel(CH, "Auto Tether", NotificationManager.IMPORTANCE_LOW));
        }
        Intent open = new Intent(this, MainActivity.class)
                .putExtra("pick_interface", true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pi = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification n = new Notification.Builder(this, CH)
                .setContentTitle("Pick your adapter's network interface")
                .setContentText("The adapter is attached but wasn't auto-detected. Tap to choose it.")
                .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build();
        nm.notify(2, n);
    }

    /** True when the phone is drawing power over USB (charging) — true even while locked, and
     *  false in host/Ethernet mode (where the phone powers the adapter rather than being charged). */
    boolean isUsbPowered() {
        try {
            android.content.Intent b = registerReceiver(null,
                new android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED));
            int p = b == null ? 0 : b.getIntExtra("plugged", 0);
            return (p & android.os.BatteryManager.BATTERY_PLUGGED_USB) != 0;
        } catch (Throwable t) { return false; }
    }

    void update(String s) {
        status = s;
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) nm.notify(1, buildNotification(s));
    }

    Notification buildNotification(String text) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm.getNotificationChannel(CH) == null) {
            nm.createNotificationChannel(new NotificationChannel(CH, "Auto Tether", NotificationManager.IMPORTANCE_LOW));
        }
        return new Notification.Builder(this, CH)
            .setContentTitle("Auto Tether")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setOngoing(true)
            .build();
    }

    @Override public IBinder onBind(Intent i) { return null; }
    @Override public void onDestroy() { loopThread = null; super.onDestroy(); }
}

package io.github.maparham.autotether;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import android.util.Log;

import java.net.NetworkInterface;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Foreground service: watches for the USB-Ethernet adapter appearing and auto-runs the
 * tether helper, so the user never has to open the app.
 */
public class TetherService extends Service {
    static final String CH = "ethtether";
    // Matches the system's ethernet/usb tether interface filter.
    static final Pattern TARGET = Pattern.compile("^(eth|usb)\\d+$");
    volatile boolean running = false;
    // How many consecutive polls an interface must be present before we tether it, so the
    // interface (and EthernetManager) has settled — tethering a half-up eth0 fails with ENODEV.
    static final int STABLE_POLLS = 3; // ~7.5s at the 2.5s poll interval
    final Map<String, Integer> presentCount = new HashMap<>();
    final Set<String> tethered = new HashSet<>();
    boolean usbTetherDone = false;
    String lastUsbSig = "";
    String status = "watching for adapter…";

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (!running) {
            running = true;
            startForeground(1, buildNotification(status));
            new Thread(this::loop).start();
        }
        return START_STICKY;
    }

    void loop() {
        while (running) {
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
                if (usbPresent && now.isEmpty()) { // skip while an eth/usb tether iface is already up
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

                if (now.isEmpty() && !usbTetherDone) update("watching for adapter…");
            } catch (Throwable t) {
                Log.e("AutoTether", "loop error", t);
            }
            try { Thread.sleep(2500); } catch (InterruptedException e) { break; }
        }
    }

    Set<String> currentTargets() throws Exception {
        Set<String> s = new HashSet<>();
        Enumeration<NetworkInterface> e = NetworkInterface.getNetworkInterfaces();
        while (e != null && e.hasMoreElements()) {
            String n = e.nextElement().getName();
            if (TARGET.matcher(n).matches()) s.add(n);
        }
        return s;
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
    @Override public void onDestroy() { running = false; super.onDestroy(); }
}

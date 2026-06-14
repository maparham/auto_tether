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
import java.util.HashSet;
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
    final Set<String> seen = new HashSet<>();
    boolean usbTetherDone = false;
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
                Set<String> now = currentTargets();
                // newly-appeared adapter → tether
                for (String iface : now) {
                    if (!seen.contains(iface)) {
                        Log.i("AutoTether", "adapter appeared: " + iface + " → tethering");
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
                seen.clear();
                seen.addAll(now);

                // USB tethering: phone plugged into a computer (peripheral, "configured").
                if (isUsbConfigured()) {
                    if (!usbTetherDone) {
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

    /** True when the phone is plugged into a computer as a USB peripheral (vs. hosting the adapter). */
    boolean isUsbConfigured() {
        try {
            android.content.Intent i = registerReceiver(null,
                new android.content.IntentFilter("android.hardware.usb.action.USB_STATE"));
            return i != null && i.getBooleanExtra("connected", false) && i.getBooleanExtra("configured", false);
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

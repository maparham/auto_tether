package io.github.maparham.autotether;

import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.util.Log;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.button.MaterialButton;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Collections;

/** Pairing (via notification reply, so the Settings pairing dialog stays open) + watcher control. */
public class MainActivity extends AppCompatActivity {
    TextView status;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        if (Build.VERSION.SDK_INT >= 33) {
            try { requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 1); } catch (Throwable ignore) {}
        }
        setContentView(R.layout.activity_main);

        ((TextView) findViewById(R.id.steps)).setText(
                "1.  Turn Wi-Fi on.\n" +
                "2.  Tap Enable auto-pairing → switch Auto Tether ON in the\n" +
                "     Accessibility list (one time).\n" +
                "3.  Settings → Developer options → Wireless debugging → ON →\n" +
                "     “Pair device with pairing code”.\n" +
                "4.  Leave that dialog open — the app reads the code and pairs itself.\n\n" +
                "Done. Plug in the adapter or a USB cable and tethering turns on by itself. " +
                "After a reboot, just turn Wireless debugging back on.");

        status = findViewById(R.id.status);

        ((MaterialButton) findViewById(R.id.pairBtn)).setOnClickListener(v -> {
            if (!isAutoPairingOn()) {
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
                setStatus("Switch \"Auto Tether\" ON in the Accessibility list, then come back.");
            } else if (!hasWifi()) {
                startActivity(new Intent(Settings.ACTION_WIFI_SETTINGS));
                setStatus("Connect to a Wi-Fi network first (pairing needs it),\nthen come back and tap again.");
            } else if (!devOptionsOn()) {
                setStatus("Enable Developer options first:\n" +
                        "Settings → About phone → tap “Build number” 7 times.\n" +
                        "Then come back and tap again.");
            } else {
                // ask the accessibility service to tap into Wireless debugging once Developer options opens
                PairAccessibilityService.navigateWdUntil = System.currentTimeMillis() + 15000;
                openWirelessDebugging();
                setStatus("Opening Wireless debugging…\n" +
                        "Tap “Pair device with pairing code” and leave that dialog open —\n" +
                        "the app reads the code and pairs itself.");
            }
        });

        ((MaterialButton) findViewById(R.id.watchBtn)).setOnClickListener(v -> startWatcher());

        startWatcher(); // ensure the background watcher is running whenever the app is opened
    }

    @Override
    protected void onResume() {
        super.onResume();
        MaterialButton pair = findViewById(R.id.pairBtn);
        pair.setText(isAutoPairingOn() ? "Auto-pairing enabled ✓" : "Enable auto-pairing");
    }

    /** Whether Developer options are enabled. */
    boolean devOptionsOn() {
        return Settings.Global.getInt(getContentResolver(),
                Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0) == 1;
    }

    /** Open the Wireless debugging screen directly, falling back to Developer options. */
    void openWirelessDebugging() {
        Intent wd = new Intent("android.settings.WIRELESS_DEBUGGING_SETTINGS")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(wd);
        } catch (Exception e) {
            try { startActivity(new Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)); }
            catch (Exception e2) { setStatus("Open Settings → Developer options → Wireless debugging manually."); }
        }
    }

    /** Whether the phone is connected to Wi-Fi (wlan has an IPv4). */
    static boolean hasWifi() {
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (ni.isUp() && !ni.isLoopback() && ni.getName().startsWith("wlan")) {
                    for (InetAddress a : Collections.list(ni.getInetAddresses()))
                        if (a instanceof Inet4Address) return true;
                }
            }
        } catch (Exception ignore) {}
        return false;
    }

    /** Whether our accessibility service is currently enabled. */
    boolean isAutoPairingOn() {
        String me = getPackageName() + "/" + PairAccessibilityService.class.getName();
        String enabled = Settings.Secure.getString(getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        return enabled != null && enabled.contains(me);
    }

    /** The phone's Wi-Fi IPv4 (where the pairing/connect services bind). */
    static String wifiIp() {
        try {
            String anyIp = null;
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (a instanceof Inet4Address && !a.isLoopbackAddress()) {
                        String ip = a.getHostAddress();
                        if (ni.getName().startsWith("wlan")) return ip;
                        if (a.isSiteLocalAddress() && anyIp == null) anyIp = ip;
                    }
                }
            }
            return anyIp;
        } catch (Exception e) { return null; }
    }

    void startWatcher() {
        Intent i = new Intent(this, TetherService.class);
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(i); else startService(i);
        setStatus("Watcher running — you can close the app.");
    }

    void setStatus(String s) {
        Log.i("AutoTether", s);
        runOnUiThread(() -> status.setText(s));
    }
}

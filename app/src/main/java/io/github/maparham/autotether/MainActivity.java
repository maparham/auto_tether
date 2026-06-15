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
        status = findViewById(R.id.status);

        ((MaterialButton) findViewById(R.id.pairBtn)).setOnClickListener(v -> onPairButton());
        ((MaterialButton) findViewById(R.id.watchBtn)).setOnClickListener(v -> startWatcher());

        refreshUi();
        startWatcher(); // ensure the background watcher is running whenever the app is opened
    }

    void refreshUi() {
        ((MaterialButton) findViewById(R.id.pairBtn)).setText(stepLabel(nextStep()));
        ((TextView) findViewById(R.id.steps)).setText(checklist());
    }

    String checklist() {
        if (isPaired()) {
            // Setup is done; only the bits that reset still matter.
            if (hasWifi() && wirelessDebuggingOn())
                return "✅  Paired & ready.\n\nPlug in the adapter or a USB cable —\ntethering turns on by itself.";
            return "Paired ✓ — to reconnect, turn these on:\n\n"
                 + mark(hasWifi())             + "Wi-Fi connected\n"
                 + mark(wirelessDebuggingOn()) + "Wireless debugging on";
        }
        // First-time setup.
        return mark(isAutoPairingOn())    + "Auto-pairing enabled\n"
             + mark(hasWifi())            + "Wi-Fi connected\n"
             + mark(devOptionsOn())       + "Developer options on\n"
             + mark(wirelessDebuggingOn())+ "Wireless debugging on\n"
             + mark(isPaired())           + "Device paired";
    }

    static String mark(boolean ok) { return ok ? "✅  " : "⬜  "; }

    // Setup steps, in prerequisite order.
    static final int ACCESSIBILITY = 0, WIFI = 1, DEVOPTS = 2, WIRELESS_DEBUG = 3, PAIR = 4, DONE = 5;

    int nextStep() {
        if (isPaired()) {
            // already set up — only the bits that reset matter
            if (!hasWifi()) return WIFI;
            if (!wirelessDebuggingOn()) return WIRELESS_DEBUG;
            return DONE;
        }
        if (!isAutoPairingOn()) return ACCESSIBILITY;
        if (!hasWifi()) return WIFI;
        if (!devOptionsOn()) return DEVOPTS;
        if (!wirelessDebuggingOn()) return WIRELESS_DEBUG;
        return PAIR;
    }

    String stepLabel(int step) {
        switch (step) {
            case ACCESSIBILITY: return "Enable auto-pairing";
            case WIFI:          return "Connect Wi-Fi";
            case DEVOPTS:       return "Enable Developer options";
            case WIRELESS_DEBUG:return "Turn on Wireless debugging";
            case PAIR:          return "Pair this device";
            default:            return "Paired ✓ — all set";
        }
    }

    void onPairButton() {
        switch (nextStep()) {
            case ACCESSIBILITY:
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
                setStatus("Switch \"Auto Tether\" ON in the Accessibility list, then come back.");
                break;
            case WIFI:
                startActivity(new Intent(Settings.ACTION_WIFI_SETTINGS));
                setStatus("Connect to a Wi-Fi network, then come back.");
                break;
            case DEVOPTS:
                setStatus("Enable Developer options first:\n" +
                        "Settings → About phone → tap “Build number” 7 times,\nthen come back.");
                break;
            case WIRELESS_DEBUG:
                PairAccessibilityService.navigateWdUntil = System.currentTimeMillis() + 15000;
                openWirelessDebugging();
                setStatus("Turn “Use wireless debugging” ON, then come back.");
                break;
            case PAIR:
                PairAccessibilityService.navigateWdUntil = System.currentTimeMillis() + 15000;
                openWirelessDebugging();
                setStatus("Tap “Pair device with pairing code” and leave it open —\nthe app pairs itself.");
                break;
            default:
                setStatus("All set ✓ Plug in the adapter or a USB cable —\ntethering turns on by itself.");
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshUi();
    }

    /** Whether Wireless debugging is enabled. */
    boolean wirelessDebuggingOn() {
        try { return Settings.Global.getInt(getContentResolver(), "adb_wifi_enabled", 0) == 1; }
        catch (Exception e) { return false; }
    }

    /** Whether this device has completed pairing at least once. */
    boolean isPaired() {
        return getSharedPreferences("autotether", MODE_PRIVATE).getBoolean("paired", false);
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

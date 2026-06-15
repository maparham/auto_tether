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
        ((MaterialButton) findViewById(R.id.watchBtn)).setOnClickListener(v -> {
            startWatcher();
            status.setText("Background watcher (re)started.");
        });

        startWatcher(); // ensure the background watcher is running whenever the app is opened
        refreshUi();
    }

    void refreshUi() {
        ((MaterialButton) findViewById(R.id.pairBtn)).setText(stepLabel(nextStep()));
        ((TextView) findViewById(R.id.steps)).setText(checklist());
        ((TextView) findViewById(R.id.cardTitle)).setText(
                isPaired() ? "Tethering status" : "Pair this device for Wireless debugging");
        status.setText(statusHint());
    }

    /** A short, state-appropriate line for the Status box (UI-element names in bold). */
    CharSequence statusHint() {
        switch (nextStep()) {
            case ACCESSIBILITY: return bold("Tap the button above → switch Auto Tether ON in the\n" +
                    "Accessibility list. It reads the pairing code for you.",
                    "Auto Tether", "Accessibility");
            case WIFI:          return isPaired()
                    ? bold("After a phone restart, turn Wi-Fi on for ~15 seconds so the app\n" +
                      "can re-arm no-Wi-Fi mode — then you can turn Wi-Fi off again.", "Wi-Fi")
                    : bold("Turn Wi-Fi on — pairing needs it.", "Wi-Fi");
            case DEVOPTS:       return bold("Enable Developer options: Settings → About phone →\n" +
                    "tap Build number 7 times.", "Developer options", "About phone", "Build number");
            case WIRELESS_DEBUG: return isPaired()
                    ? bold("Wireless debugging turned off after the reboot.\n" +
                      "Tap the button above → it opens Developer options:\n" +
                      "  1. Tap Wireless debugging\n" +
                      "  2. Turn Use wireless debugging ON\n" +
                      "Then it reconnects on its own.",
                      "Wireless debugging", "Use wireless debugging", "Developer options")
                    : bold("Tap the button above → it opens Developer options:\n" +
                      "  1. Tap Wireless debugging\n" +
                      "  2. Turn Use wireless debugging ON\n" +
                      "  3. Tap Pair device with pairing code — keep it open\n" +
                      "The app reads the code and pairs itself.",
                      "Wireless debugging", "Use wireless debugging", "Pair device with pairing code", "Developer options");
            case PAIR:          return bold("Tap the button above → it opens Developer options:\n" +
                    "  1. Tap Wireless debugging\n" +
                    "  2. Tap Pair device with pairing code — keep it open\n" +
                    "The app reads the code and pairs itself.\n" +
                    "(It tries steps 1–2 for you; do them yourself if needed.)",
                    "Wireless debugging", "Pair device with pairing code", "Developer options");
            default:            return bold("Ready ✓  Plug in the adapter or a USB cable —\n" +
                    "tethering turns on automatically. Wi-Fi can be off; you can\n" +
                    "close the app. (After a phone restart, see the note above.)", "");
        }
    }

    /** Returns text with each given phrase styled bold. */
    static CharSequence bold(String text, String... phrases) {
        android.text.SpannableStringBuilder sb = new android.text.SpannableStringBuilder(text);
        for (String p : phrases) {
            if (p == null || p.isEmpty()) continue;
            int i = 0;
            while ((i = text.indexOf(p, i)) >= 0) {
                sb.setSpan(new android.text.style.StyleSpan(android.graphics.Typeface.BOLD),
                        i, i + p.length(), android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                i += p.length();
            }
        }
        return sb;
    }

    String checklist() {
        if (isPaired()) {
            // Setup is done. Once the no-Wi-Fi door is armed, nothing else is needed.
            if (AdbRunner.tcpipArmed)
                return "✅  Paired & ready — works without Wi-Fi.\n\nPlug in the adapter or a USB cable —\ntethering turns on by itself.";
            // Fresh boot: the no-Wi-Fi door resets on restart; re-arm it with a brief Wi-Fi moment.
            return "Paired ✓ — after a restart, turn these on briefly so it\ncan re-arm (then Wi-Fi can go back off):\n\n"
                 + mark(hasWifi())             + "Wi-Fi connected\n"
                 + mark(wirelessDebuggingOn()) + "Wireless debugging on";
        }
        // First-time setup.
        return mark(isAutoPairingOn())    + "Accessibility access on\n"
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
            // Once the loopback door is open (adb tcpip armed), tethering needs no Wi-Fi at all.
            if (AdbRunner.tcpipArmed) return DONE;
            // Otherwise (fresh boot — tcpip resets) we need Wi-Fi + Wireless debugging briefly to re-arm.
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
            case ACCESSIBILITY: return "Turn on Accessibility access";
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
                setStatus(bold("In the list that opened, tap Auto Tether and switch it ON,\n" +
                        "then come back. (This lets the app read the pairing code for you.)", "Auto Tether"));
                break;
            case WIFI:
                startActivity(new Intent(Settings.ACTION_WIFI_SETTINGS));
                setStatus(bold("Connect to a Wi-Fi network, then come back.", "Wi-Fi"));
                break;
            case DEVOPTS:
                setStatus(bold("Enable Developer options first:\n" +
                        "Settings → About phone → tap Build number 7 times, then come back.",
                        "Developer options", "About phone", "Build number"));
                break;
            case WIRELESS_DEBUG:
            case PAIR:
                PairAccessibilityService.navigateWdUntil = System.currentTimeMillis() + 25000;
                CharSequence steps = statusHint(); // capture the detailed steps before we navigate away
                openWirelessDebugging();
                setStatus(steps);
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
    }

    void setStatus(CharSequence s) {
        Log.i("AutoTether", s.toString());
        runOnUiThread(() -> status.setText(s));
    }
}

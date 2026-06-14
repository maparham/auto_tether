package io.github.maparham.autotether;

import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
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
                "2.  Tap Start pairing below.\n" +
                "3.  Settings → Developer options → Wireless debugging → ON →\n" +
                "     “Pair device with pairing code”.\n" +
                "4.  Pull down the notification shade (keep that dialog open), open\n" +
                "     the Auto Tether reply, and type:  PORT  CODE  → send.\n\n" +
                "Done. Plug in the adapter or a USB cable and tethering turns on by itself. " +
                "After a reboot, just turn Wireless debugging back on.");

        status = findViewById(R.id.status);

        ((MaterialButton) findViewById(R.id.pairBtn)).setOnClickListener(v -> {
            PairReceiver.show(getApplicationContext(),
                    "Open the Wireless-debugging pair dialog, then reply here with:  PORT  CODE");
            setStatus("Pairing notification posted.\nOpen the pair dialog, pull down the shade, and reply.");
        });

        ((MaterialButton) findViewById(R.id.watchBtn)).setOnClickListener(v -> startWatcher());
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

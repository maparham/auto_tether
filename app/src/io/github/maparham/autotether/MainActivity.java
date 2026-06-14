package io.github.maparham.autotether;

import android.app.Activity;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Starts the background watcher and offers a manual "Tether now" button. */
public class MainActivity extends Activity {
    TextView tv;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        // Ask for notification permission (needed to show the foreground-service notice on Android 13+).
        if (Build.VERSION.SDK_INT >= 33) {
            try { requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 1); } catch (Throwable t) {}
        }

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(48, 96, 48, 48);

        tv = new TextView(this);
        tv.setTextSize(16);
        tv.setText("Auto Tether\n\nThe background watcher auto-enables Ethernet tethering whenever you plug the adapter in.");
        root.addView(tv);

        Button startBtn = new Button(this);
        startBtn.setText("Start / restart watcher");
        startBtn.setOnClickListener(this::startWatcher);
        root.addView(startBtn);

        Button nowBtn = new Button(this);
        nowBtn.setText("Tether now");
        nowBtn.setOnClickListener(v -> new Thread(() -> {
            try { log("manual: " + AdbRunner.tether(this)); }
            catch (Exception e) { log("manual error: " + e); }
        }).start());
        root.addView(nowBtn);

        setContentView(root);
        startWatcher(null);
    }

    void startWatcher(View v) {
        Intent i = new Intent(this, TetherService.class);
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(i); else startService(i);
        log("watcher started — you can close this app.");
    }

    void log(String s) {
        Log.i("AutoTether", s);
        runOnUiThread(() -> tv.setText(tv.getText() + "\n• " + s));
    }
}

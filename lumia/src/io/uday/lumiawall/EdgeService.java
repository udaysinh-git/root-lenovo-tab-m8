package io.uday.lumiawall;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.os.IBinder;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;

/**
 * With the status bar hidden everywhere (the WallBars overlay sets its height to 0), there is nothing left to pull the
 * notification shade from. This keeps an invisible strip along the top edge, above every app, and a downward swipe on
 * it opens the shade. It sits below the shade itself, so the shade works normally once open.
 * Needs "display over other apps" (appops SYSTEM_ALERT_WINDOW, granted once over adb).
 * It runs as a foreground service: Android stops plain background services a minute after the app leaves the screen
 * ("Stopping service due to app idle"). Its notification uses a minimum-importance channel, so it only shows as one
 * collapsed line in the shade. Note: the Settings app hides all third-party overlays while it's open (anti-tapjacking),
 * so the strip doesn't work inside Settings.
 */
public class EdgeService extends Service {
    private static final int STRIP_DP = 12, TRIGGER_DP = 18;
    private View strip;

    static void start(Context c) {
        if (android.provider.Settings.canDrawOverlays(c)) c.startForegroundService(new Intent(c, EdgeService.class));
    }

    @Override public IBinder onBind(Intent i) { return null; }

    @Override public int onStartCommand(Intent i, int flags, int id) {
        android.app.NotificationManager nm = getSystemService(android.app.NotificationManager.class);
        nm.createNotificationChannel(new android.app.NotificationChannel("edge", "Top edge (notifications)",
                android.app.NotificationManager.IMPORTANCE_MIN));
        startForeground(2, new android.app.Notification.Builder(this, "edge")
                .setSmallIcon(android.R.drawable.arrow_down_float)
                .setContentTitle("Swipe down from the top edge for notifications")
                .setOngoing(true).build());
        if (strip == null) addStrip();
        return START_STICKY;
    }

    private void addStrip() {
        float d = getResources().getDisplayMetrics().density;
        strip = new View(this);
        strip.setOnTouchListener(new View.OnTouchListener() {
            float downY;
            boolean fired;

            @Override public boolean onTouch(View v, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN: downY = e.getRawY(); fired = false; break;
                    case MotionEvent.ACTION_MOVE:
                        if (!fired && e.getRawY() - downY > TRIGGER_DP * d) {
                            fired = true;
                            new Thread(() -> Root.run("cmd statusbar expand-notifications"), "shade").start();
                        }
                        break;
                }
                return true;
            }
        });
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT, Math.round(STRIP_DP * d),
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP;
        lp.setTitle("WallEdge");
        ((WindowManager) getSystemService(WINDOW_SERVICE)).addView(strip, lp);
    }

    @Override public void onDestroy() {
        if (strip != null) ((WindowManager) getSystemService(WINDOW_SERVICE)).removeView(strip);
        super.onDestroy();
    }
}

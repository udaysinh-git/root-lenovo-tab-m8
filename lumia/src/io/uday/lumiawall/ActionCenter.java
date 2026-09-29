package io.uday.lumiawall;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.WindowManager;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * WP8.1-style action center: pull down from the top band of the home screen. Quick actions use root
 * (see {@link Root}) because Android 11 doesn't let apps toggle radios or system brightness.
 *
 *  camera       stop/start the built-in camera server (and tell the wall-boot watchdog to leave it off)
 *  wi-fi        svc wifi
 *  bluetooth    svc bluetooth
 *  charge       hold 50-60% (charge-limit.sh) or charge to full, e.g. before taking the tablet off the wall
 *  night        drop to the lowest brightness and back
 *  screen off   sleep now (the power button wakes it)
 * plus a brightness slider and links: all settings, restart display (spacedesk), restart tablet (hold).
 */
final class ActionCenter extends FrameLayout {
    static final String PREFS = "wall";

    private final Activity act;
    private final SharedPreferences prefs;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Handler bg;
    private final View scrim;
    private final LinearLayout panel;
    private final TextView time, date, battery;
    private final Quick camera, wifi, bluetooth, charge, night;
    private final Slider brightness;
    private final TextView brightnessPct;
    private float openness;                 // 0 closed .. 1 open
    private ValueAnimator anim;

    // pull gesture (fed from the activity's dispatchTouchEvent)
    private float downX, downY;
    private boolean tracking, pulling;
    private VelocityTracker vt;
    private final int slop;

    ActionCenter(Activity a) {
        super(a);
        act = a;
        prefs = a.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        slop = ViewConfiguration.get(a).getScaledTouchSlop();
        HandlerThread t = new HandlerThread("action-center");
        t.start();
        bg = new Handler(t.getLooper());

        scrim = new View(a);
        scrim.setBackgroundColor(0xFF000000);
        scrim.setOnClickListener(v -> close());
        addView(scrim, new LayoutParams(-1, -1));

        panel = new LinearLayout(a);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackgroundColor(0xFF121212);
        panel.setPadding(Metro.dp(22), Metro.dp(16), Metro.dp(22), Metro.dp(10));
        panel.setClickable(true);                               // taps inside don't fall through to the scrim

        // header: time/date left, battery right
        LinearLayout head = new LinearLayout(a);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.BOTTOM);
        time = Tile.text(a, "", 40, Metro.light, Metro.TEXT);
        date = Tile.text(a, "", 17, Metro.semilight, Metro.TEXT_DIM);
        LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(0, -2, 1f);
        dl.leftMargin = Metro.dp(14);
        dl.bottomMargin = Metro.dp(7);
        battery = Tile.text(a, "", 17, Metro.semilight, Metro.TEXT_DIM);
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(-2, -2);
        bl.bottomMargin = Metro.dp(7);
        head.addView(time);
        head.addView(date, dl);
        head.addView(battery, bl);
        panel.addView(head);

        // quick actions
        LinearLayout row = new LinearLayout(a);
        row.setOrientation(LinearLayout.HORIZONTAL);
        camera = new Quick(a, Glyph.CAMERA, "camera");
        wifi = new Quick(a, Glyph.WIFI, "wi-fi");
        bluetooth = new Quick(a, Glyph.BLUETOOTH, "bluetooth");
        charge = new Quick(a, Glyph.BOLT, "charge");
        night = new Quick(a, Glyph.MOON, "night");
        Quick sleep = new Quick(a, Glyph.POWER, "screen off");
        sleep.set(false, "");
        for (Quick q : new Quick[]{camera, wifi, bluetooth, charge, night, sleep}) {
            LinearLayout.LayoutParams ql = new LinearLayout.LayoutParams(0, Metro.dp(96), 1f);
            ql.rightMargin = q == sleep ? 0 : Metro.dp(6);
            row.addView(q, ql);
        }
        LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(-1, -2);
        rl.topMargin = Metro.dp(14);
        panel.addView(row, rl);

        camera.setOnClickListener(v -> toggleCamera());
        wifi.setOnClickListener(v -> toggleRadio(wifi, "wifi"));
        bluetooth.setOnClickListener(v -> toggleRadio(bluetooth, "bluetooth"));
        charge.setOnClickListener(v -> toggleCharge());
        night.setOnClickListener(v -> toggleNight());
        sleep.setOnClickListener(v -> { close(); bg.postDelayed(() -> Root.run("input keyevent 223"), 350); });

        // brightness
        LinearLayout br = new LinearLayout(a);
        br.setOrientation(LinearLayout.HORIZONTAL);
        br.setGravity(Gravity.CENTER_VERTICAL);
        br.addView(new Glyph(a, Glyph.SUN), new LinearLayout.LayoutParams(Metro.dp(26), Metro.dp(26)));
        brightness = new Slider(a);
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(0, Metro.dp(44), 1f);
        sl.leftMargin = Metro.dp(14);
        sl.rightMargin = Metro.dp(14);
        br.addView(brightness, sl);
        brightnessPct = Tile.text(a, "", 17, Metro.semilight, Metro.TEXT_DIM);
        br.addView(brightnessPct, new LinearLayout.LayoutParams(Metro.dp(52), -2));
        LinearLayout.LayoutParams brl = new LinearLayout.LayoutParams(-1, -2);
        brl.topMargin = Metro.dp(12);
        panel.addView(br, brl);

        // links
        LinearLayout links = new LinearLayout(a);
        links.setOrientation(LinearLayout.HORIZONTAL);
        links.addView(link("all settings", v -> {
            close();
            act.startActivity(new Intent(android.provider.Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        }));
        links.addView(link("restart display", v -> {
            close();
            toast("restarting spacedesk");
            bg.post(() -> Root.run("am force-stop ph.spacedesk.beta; sleep 1; "
                    + "monkey -p ph.spacedesk.beta -c android.intent.category.LAUNCHER 1"));
        }));
        TextView reboot = link("restart tablet", v -> toast("hold to restart the tablet"));
        reboot.setOnLongClickListener(v -> {
            toast("restarting…");
            bg.postDelayed(() -> Root.run("svc power reboot || reboot"), 600);
            return true;
        });
        links.addView(reboot);
        LinearLayout.LayoutParams ll = new LinearLayout.LayoutParams(-1, -2);
        ll.topMargin = Metro.dp(6);
        panel.addView(links, ll);

        // grab handle
        View handle = new View(a);
        handle.setBackgroundColor(Metro.TEXT_FAINT);
        LinearLayout.LayoutParams hl = new LinearLayout.LayoutParams(Metro.dp(44), Metro.dp(3));
        hl.gravity = Gravity.CENTER_HORIZONTAL;
        hl.topMargin = Metro.dp(8);
        panel.addView(handle, hl);

        addView(panel, new LayoutParams(-1, -2, Gravity.TOP));
        setVisibility(GONE);
        applyOpenness(0);
    }

    // ------------------------------------------------------------------ opening / closing

    boolean isOpen() { return getVisibility() == VISIBLE; }

    /** Pull-down detection for the home screen. Returns true once the gesture belongs to the action center. */
    boolean interceptPull(MotionEvent e, float bandPx) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                tracking = !isOpen() && e.getY() < bandPx;
                pulling = false;
                downX = e.getX(); downY = e.getY();
                if (tracking) { vt = VelocityTracker.obtain(); vt.addMovement(e); }
                return false;
            case MotionEvent.ACTION_MOVE:
                if (!tracking) return false;
                vt.addMovement(e);
                float dy = e.getY() - downY, dx = e.getX() - downX;
                if (!pulling && dy > slop && dy > Math.abs(dx) * 1.5f) {
                    pulling = true;
                    refresh();
                    setVisibility(VISIBLE);
                }
                if (pulling) applyOpenness(Math.max(0f, Math.min(1f, dy / Math.max(1, panel.getHeight()))));
                return pulling;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                boolean was = pulling;
                if (pulling) {
                    vt.computeCurrentVelocity(1000);
                    float v = vt.getYVelocity() / Metro.density;
                    if (v > 250 || (v > -250 && openness > 0.4f)) open(); else close();
                }
                tracking = pulling = false;
                if (vt != null) { vt.recycle(); vt = null; }
                return was;
        }
        return false;
    }

    void open() {
        if (!isOpen()) { refresh(); setVisibility(VISIBLE); }
        animateTo(1f, 380, Metro.ENTER);
    }

    void close() {
        animateTo(0f, 240, new DecelerateInterpolator());
    }

    private void animateTo(float target, long ms, android.animation.TimeInterpolator interp) {
        if (anim != null) anim.cancel();
        anim = ValueAnimator.ofFloat(openness, target);
        anim.setDuration(ms);
        anim.setInterpolator(interp);
        anim.addUpdateListener(v -> applyOpenness((Float) v.getAnimatedValue()));
        anim.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(android.animation.Animator an) {
                if (target == 0f && openness == 0f) setVisibility(GONE);
            }
        });
        anim.start();
    }

    private void applyOpenness(float f) {
        openness = f;
        int h = panel.getHeight() > 0 ? panel.getHeight() : Metro.dp(330);
        panel.setTranslationY(-h * (1f - f));
        scrim.setAlpha(0.6f * f);
    }

    // Swipe up on the panel closes it (the panel follows the finger).
    private float pDownY;
    private boolean pDragging;

    @Override public boolean onInterceptTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: pDownY = e.getY(); pDragging = false; break;
            case MotionEvent.ACTION_MOVE:
                if (e.getY() - pDownY < -slop) { pDragging = true; return true; }
        }
        return false;
    }

    @Override public boolean onTouchEvent(MotionEvent e) {
        if (!pDragging) return super.onTouchEvent(e);
        float dy = Math.min(0, e.getY() - pDownY);
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_MOVE:
                applyOpenness(Math.max(0f, 1f + dy / Math.max(1, panel.getHeight())));
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                pDragging = false;
                if (openness < 0.75f) close(); else open();
                return true;
        }
        return true;
    }

    // ------------------------------------------------------------------ state

    /** Reads everything once per opening (one su call) and paints the tiles. */
    void refresh() {
        time.setText(new SimpleDateFormat("H:mm", Locale.getDefault()).format(new Date()));
        date.setText(new SimpleDateFormat("EEEE, d MMMM", Locale.getDefault()).format(new Date()).toLowerCase(Locale.getDefault()));
        camera.set(cameraEnabled(), cameraEnabled() ? "on" : "off");
        night.set(prefs.getInt("night_restore", -1) >= 0, "");
        bg.post(() -> {
            String[] r = Root.run("settings get global wifi_on; settings get global bluetooth_on; "
                    + "settings get system screen_brightness; "
                    + "[ -f " + Root.FLAGS + "/charge.full ] && echo full || echo limit; "
                    + "cat /sys/class/power_supply/battery/capacity; cat /sys/class/power_supply/battery/status")
                    .split("\\s+");
            if (r.length < 6) {
                ui.post(() -> toast("no root access, so quick settings are read-only"));
                return;
            }
            ui.post(() -> {
                wifi.set(!"0".equals(r[0]), "");
                bluetooth.set(!"0".equals(r[1]), "");
                int b = parse(r[2], 128);
                brightness.setValue(toSlider(b));
                brightnessPct.setText(Math.round(toSlider(b) * 100) + "%");
                boolean full = "full".equals(r[3]);
                charge.set(!full, full ? "to 100%" : "50–60%");
                battery.setText(r[4] + "%  ·  " + r[5].toLowerCase(Locale.getDefault()).replace("not charging", "holding"));
            });
        });
    }

    static boolean cameraEnabled(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("camera", true);
    }

    private boolean cameraEnabled() { return cameraEnabled(act); }

    // ------------------------------------------------------------------ actions

    private void toggleCamera() {
        boolean on = !cameraEnabled();
        prefs.edit().putBoolean("camera", on).apply();
        camera.set(on, on ? "on" : "off");
        if (on) CameraStreamService.start(act);
        else act.stopService(new Intent(act, CameraStreamService.class));
        // the wall-boot watchdog restarts a missing streamer unless this flag exists
        bg.post(() -> Root.run(on ? "rm -f " + Root.FLAGS + "/camera.off"
                : "mkdir -p " + Root.FLAGS + " && touch " + Root.FLAGS + "/camera.off"));
        toast(on ? "camera server on" : "camera server off · the laptop's Tablet Camera goes to standby");
    }

    private void toggleRadio(Quick q, String svc) {
        boolean on = !q.on;
        q.set(on, "");
        bg.post(() -> Root.run("svc " + svc + (on ? " enable" : " disable")));
    }

    private void toggleCharge() {
        boolean limit = !charge.on;
        charge.set(limit, limit ? "50–60%" : "to 100%");
        // charge-limit.sh reads the flag; also switch charging right away instead of waiting for its next check
        bg.post(() -> Root.run(limit
                ? "rm -f " + Root.FLAGS + "/charge.full; [ $(cat /sys/class/power_supply/battery/capacity) -ge 60 ] "
                    + "&& echo '0 1' > /proc/mtk_battery_cmd/current_cmd"
                : "mkdir -p " + Root.FLAGS + " && touch " + Root.FLAGS + "/charge.full; "
                    + "echo '0 0' > /proc/mtk_battery_cmd/current_cmd"));
        toast(limit ? "holding the battery at 50–60%" : "charging to 100% until you switch this back");
    }

    private void toggleNight() {
        int restore = prefs.getInt("night_restore", -1);
        if (restore < 0) {
            int cur = fromSlider(brightness.value);
            prefs.edit().putInt("night_restore", cur).apply();
            setBrightness(2);
            night.set(true, "");
        } else {
            prefs.edit().putInt("night_restore", -1).apply();
            setBrightness(restore);
            night.set(false, "");
        }
    }

    private void setBrightness(int v) {
        brightness.setValue(toSlider(v));
        brightnessPct.setText(Math.round(toSlider(v) * 100) + "%");
        bg.post(() -> Root.run("settings put system screen_brightness_mode 0; settings put system screen_brightness " + v));
    }

    // Perceptual mapping: the bottom of the slider gets most of the travel (that's where a dim room lives).
    private static float toSlider(int b) { return (float) Math.sqrt(Math.max(0, b - 1) / 254f); }
    private static int fromSlider(float f) { return 1 + Math.round(254 * f * f); }

    private static int parse(String s, int def) {
        try { return Integer.parseInt(s.trim()); } catch (Exception e) { return def; }
    }

    private TextView link(String text, OnClickListener l) {
        TextView t = Tile.text(getContext(), text, 18, Metro.semilight, Metro.TEXT_DIM);
        t.setPadding(0, Metro.dp(10), Metro.dp(30), Metro.dp(10));
        t.setOnTouchListener(Metro.TILT);
        t.setOnClickListener(l);
        return t;
    }

    private void toast(String s) { Toast.makeText(act, s, Toast.LENGTH_SHORT).show(); }

    // ------------------------------------------------------------------ views

    /** WP8.1 quick action: accent when on, charcoal when off; glyph top-left, label bottom-left. */
    private static final class Quick extends FrameLayout {
        boolean on;
        private final TextView label, sub;

        Quick(Context c, int glyph, String name) {
            super(c);
            Glyph g = new Glyph(c, glyph);
            LayoutParams gl = new LayoutParams(Metro.dp(30), Metro.dp(30));
            gl.leftMargin = Metro.dp(10);
            gl.topMargin = Metro.dp(10);
            addView(g, gl);
            LinearLayout st = new LinearLayout(c);
            st.setOrientation(LinearLayout.VERTICAL);
            label = Tile.text(c, name, 15, Metro.semilight, Metro.TEXT);
            sub = Tile.text(c, "", 12, Metro.semilight, Metro.TEXT_DIM);
            st.addView(label);
            st.addView(sub);
            LayoutParams sl = new LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.START);
            sl.leftMargin = Metro.dp(10);
            sl.bottomMargin = Metro.dp(7);
            addView(st, sl);
            setOnTouchListener(Metro.TILT);
            set(false, "");
        }

        void set(boolean isOn, String detail) {
            on = isOn;
            setBackgroundColor(isOn ? Metro.COBALT : 0xFF2A2A2A);
            sub.setText(detail);
            sub.setVisibility(detail.isEmpty() ? GONE : VISIBLE);
        }
    }

    /**
     * Thin WP-style slider. While dragging, only this window's brightness changes (instant, no su per frame);
     * on release the system setting is written once so it also applies to spacedesk and everything else.
     */
    private final class Slider extends View {
        float value;
        private final Paint track = new Paint(), fill = new Paint(), thumb = new Paint();

        Slider(Context c) {
            super(c);
            track.setColor(0xFF3A3A3A);
            fill.setColor(Metro.COBALT);
            thumb.setColor(Metro.TEXT);
        }

        void setValue(float v) { value = Math.max(0f, Math.min(1f, v)); invalidate(); }

        @Override protected void onDraw(Canvas c) {
            float h = Metro.dp(4), cy = getHeight() / 2f, x = value * getWidth();
            c.drawRect(0, cy - h / 2, getWidth(), cy + h / 2, track);
            c.drawRect(0, cy - h / 2, x, cy + h / 2, fill);
            c.drawRect(x - Metro.dp(5), cy - Metro.dp(14), x + Metro.dp(5), cy + Metro.dp(14), thumb);
        }

        @Override public boolean onTouchEvent(MotionEvent e) {
            setValue(e.getX() / getWidth());
            brightnessPct.setText(Math.round(value * 100) + "%");
            WindowManager.LayoutParams lp = act.getWindow().getAttributes();
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    getParent().requestDisallowInterceptTouchEvent(true);
                    // fall through
                case MotionEvent.ACTION_MOVE:
                    lp.screenBrightness = Math.max(0.01f, fromSlider(value) / 255f);
                    act.getWindow().setAttributes(lp);
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    final int b = fromSlider(value);
                    if (prefs.getInt("night_restore", -1) >= 0) { prefs.edit().putInt("night_restore", -1).apply(); night.set(false, ""); }
                    bg.post(() -> {
                        Root.run("settings put system screen_brightness_mode 0; settings put system screen_brightness " + b);
                        ui.post(() -> {   // hand control back to the system setting we just wrote
                            WindowManager.LayoutParams l2 = act.getWindow().getAttributes();
                            l2.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE;
                            act.getWindow().setAttributes(l2);
                        });
                    });
                    return true;
            }
            return true;
        }
    }
}

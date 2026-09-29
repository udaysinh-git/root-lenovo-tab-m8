package io.uday.lumiawall;

import android.app.Activity;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.util.Locale;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * The "windows" panorama section (left of grace wall): the laptop's vitals and controls, through WallBridge.
 *   left   CPU / GPU / RAM meters with 60 s sparklines, per-core bars, battery, network, uptime
 *   right  sound output picker, volume + mute, then tiles: bluetooth, mic, power mode, lock, sleep (hold);
 *          laptop screen brightness
 * Polls /sys/stats every second and /sys/state every 3 s, only while the section is on screen.
 */
final class WindowsSection {
    private static final String BASE = MusicBridge.BASE;

    private final Activity act;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Handler bg, cmds;                  // polls and commands on separate threads: a tap never waits
    private boolean active;
    private long lastState;
    // A state read that started before the latest tap would undo it (optimistic UI, then stale truth). Every command
    // bumps cmdSeq; a read is only applied if no command was sent while it was in flight.
    private volatile int cmdSeq;

    // vitals
    private final TextView cpuVal, cpuSub, gpuVal, gpuSub, ramVal, ramSub, footer, heading;
    private final Spark cpuSpark, gpuSpark, ramSpark;
    private final Cores cores;

    // controls
    private final LinearLayout outputs;
    private final MetroSlider volume, brightness;
    private final TextView volumePct, brightnessPct;
    private final Glyph muteGlyph;
    private final Toggle bt, mic, power;
    private boolean muted;
    private String powerMode = "balanced";

    WindowsSection(Activity a, FrameLayout body) {
        act = a;
        HandlerThread t = new HandlerThread("windows-poll");
        t.start();
        bg = new Handler(t.getLooper());
        HandlerThread tc = new HandlerThread("windows-cmd");
        tc.start();
        cmds = new Handler(tc.getLooper());

        ScrollView scroll = new ScrollView(a);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        LinearLayout cols = new LinearLayout(a);
        cols.setOrientation(LinearLayout.HORIZONTAL);
        scroll.addView(cols, new FrameLayout.LayoutParams(-1, -2));
        body.addView(scroll, new FrameLayout.LayoutParams(-1, -1));

        // ---------------- left: vitals
        LinearLayout left = vertical(a);
        heading = Tile.text(a, "", 15, Metro.semilight, Metro.TEXT_FAINT);
        left.addView(heading);
        LinearLayout meters = new LinearLayout(a);
        meters.setOrientation(LinearLayout.HORIZONTAL);
        cpuSpark = new Spark(a, Metro.COBALT);
        gpuSpark = new Spark(a, Metro.EMERALD);
        ramSpark = new Spark(a, Metro.VIOLET);
        TextView[] cpu = meter(a, meters, "cpu", cpuSpark);
        TextView[] gpu = meter(a, meters, "gpu", gpuSpark);
        TextView[] ram = meter(a, meters, "memory", ramSpark);
        cpuVal = cpu[0]; cpuSub = cpu[1];
        gpuVal = gpu[0]; gpuSub = gpu[1];
        ramVal = ram[0]; ramSub = ram[1];
        LinearLayout.LayoutParams ml = new LinearLayout.LayoutParams(-1, -2);
        ml.topMargin = Metro.dp(6);
        left.addView(meters, ml);
        cores = new Cores(a);
        LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(-1, Metro.dp(44));
        cl.topMargin = Metro.dp(14);
        cl.rightMargin = Metro.dp(12);
        left.addView(cores, cl);
        TextView coresLabel = Tile.text(a, "cores", 13, Metro.semilight, Metro.TEXT_FAINT);
        left.addView(coresLabel);
        footer = Tile.text(a, "", 15, Metro.semilight, Metro.TEXT_DIM);
        footer.setLineSpacing(Metro.dp(3), 1f);
        LinearLayout.LayoutParams fl = new LinearLayout.LayoutParams(-1, -2);
        fl.topMargin = Metro.dp(14);
        left.addView(footer, fl);
        LinearLayout.LayoutParams ll = new LinearLayout.LayoutParams(0, -2, 1.05f);
        ll.rightMargin = Metro.dp(24);
        cols.addView(left, ll);

        // ---------------- right: controls
        LinearLayout right = vertical(a);
        right.addView(Tile.text(a, "sound output", 15, Metro.semilight, Metro.TEXT_FAINT));
        outputs = vertical(a);
        LinearLayout.LayoutParams ol = new LinearLayout.LayoutParams(-1, -2);
        ol.topMargin = Metro.dp(4);
        right.addView(outputs, ol);

        LinearLayout vol = row(a);
        FrameLayout muteBtn = new FrameLayout(a);
        muteGlyph = new Glyph(a, Glyph.SPEAKER);
        muteBtn.addView(muteGlyph, new FrameLayout.LayoutParams(Metro.dp(26), Metro.dp(26), Gravity.CENTER));
        muteBtn.setOnTouchListener(Metro.TILT);
        muteBtn.setOnClickListener(v -> {
            muted = !muted;
            muteGlyph.setLevel(muted ? 0f : -1f);
            cmd("mute?on=" + (muted ? 1 : 0));
        });
        vol.addView(muteBtn, new LinearLayout.LayoutParams(Metro.dp(36), Metro.dp(40)));
        volume = new MetroSlider(a, Metro.COBALT);
        volumePct = Tile.text(a, "", 15, Metro.semilight, Metro.TEXT_DIM);
        volume.setListener(throttled("volume?v=", volumePct));
        vol.addView(volume, new LinearLayout.LayoutParams(0, Metro.dp(40), 1f));
        LinearLayout.LayoutParams vpl = new LinearLayout.LayoutParams(Metro.dp(50), -2);
        vpl.leftMargin = Metro.dp(8);
        vol.addView(volumePct, vpl);
        right.addView(vol);

        LinearLayout tiles = row(a);
        bt = new Toggle(a, Glyph.BLUETOOTH, "bluetooth");
        mic = new Toggle(a, Glyph.MIC, "mic");
        power = new Toggle(a, Glyph.BOLT, "power");
        Toggle lock = new Toggle(a, Glyph.LOCK, "lock");
        Toggle sleep = new Toggle(a, Glyph.MOON, "sleep");
        lock.set(false, "");
        sleep.set(false, "hold");
        Toggle[] all = {bt, mic, power, lock, sleep};
        for (Toggle q : all) {
            LinearLayout.LayoutParams ql = new LinearLayout.LayoutParams(0, Metro.dp(72), 1f);
            ql.rightMargin = q == sleep ? 0 : Metro.dp(5);
            tiles.addView(q, ql);
        }
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(-1, -2);
        tl.topMargin = Metro.dp(8);
        right.addView(tiles, tl);
        bt.setOnClickListener(v -> { bt.set(!bt.on, ""); cmd("bluetooth?on=" + (bt.on ? 1 : 0)); });
        mic.setOnClickListener(v -> {
            boolean nowMuted = mic.on;                                // on = live mic
            mic.set(!nowMuted, nowMuted ? "muted" : "");
            cmd("micmute?on=" + (nowMuted ? 1 : 0));
        });
        power.setOnClickListener(v -> {
            String next = powerMode.equals("efficiency") ? "balanced" : powerMode.equals("balanced") ? "performance" : "efficiency";
            setPower(next);
            cmd("power?mode=" + next);
        });
        lock.setOnClickListener(v -> cmd("lock"));
        sleep.setOnClickListener(v -> toast("hold to put the laptop to sleep"));
        sleep.setOnLongClickListener(v -> { toast("laptop going to sleep"); cmd("sleep"); return true; });

        LinearLayout br = row(a);
        FrameLayout sun = new FrameLayout(a);
        sun.addView(new Glyph(a, Glyph.SUN), new FrameLayout.LayoutParams(Metro.dp(24), Metro.dp(24), Gravity.CENTER));
        br.addView(sun, new LinearLayout.LayoutParams(Metro.dp(36), Metro.dp(40)));
        brightness = new MetroSlider(a, Metro.AMBER);
        brightnessPct = Tile.text(a, "", 15, Metro.semilight, Metro.TEXT_DIM);
        brightness.setListener(throttled("brightness?v=", brightnessPct));
        br.addView(brightness, new LinearLayout.LayoutParams(0, Metro.dp(40), 1f));
        LinearLayout.LayoutParams bpl = new LinearLayout.LayoutParams(Metro.dp(50), -2);
        bpl.leftMargin = Metro.dp(8);
        br.addView(brightnessPct, bpl);
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(-1, -2);
        bl.topMargin = Metro.dp(6);
        right.addView(br, bl);
        TextView brLabel = Tile.text(a, "laptop screen", 13, Metro.semilight, Metro.TEXT_FAINT);
        brLabel.setPadding(Metro.dp(36), 0, 0, 0);
        right.addView(brLabel);

        LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(0, -2, 1f);
        rl.rightMargin = Metro.dp(16);
        cols.addView(right, rl);

        heading.setText("connecting to the laptop…");
    }

    // ------------------------------------------------------------------ polling

    /** On while the section is visible and the home screen is in front. */
    void setActive(boolean on) {
        if (on == active) return;
        active = on;
        if (on) { lastState = 0; ui.post(tick); }
        else ui.removeCallbacks(tick);
    }

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!active) return;
            bg.post(() -> {
                String s = get("/sys/stats");
                ui.post(() -> showStats(s));
                if (System.currentTimeMillis() - lastState > 3000) {
                    lastState = System.currentTimeMillis();
                    final int seq = cmdSeq;
                    String st = get("/sys/state");
                    ui.post(() -> { if (seq == cmdSeq) showState(st); });
                }
            });
            ui.postDelayed(this, 1000);
        }
    };

    private static String get(String path) {
        try { return Probes.get(BASE + path, 3000); } catch (Exception e) { return null; }
    }

    private void cmd(final String c) {
        cmdSeq++;
        cmds.post(() -> {
            if (get("/sys/cmd/" + c) == null) ui.post(() -> toast("the laptop didn't accept that"));
            cmdSeq++;                                               // reads started before it finished are stale too
            lastState = System.currentTimeMillis() - 2000;          // re-read the real state in about a second
        });
    }

    /** Sliders send while dragging (at most every 150 ms) and once more on release. */
    private MetroSlider.Listener throttled(final String prefix, final TextView pct) {
        final long[] last = {0};
        return (v, released) -> {
            int p = Math.round(v * 100);
            pct.setText(p + "%");
            long now = System.currentTimeMillis();
            if (released || now - last[0] > 150) {
                last[0] = now;
                cmd(prefix + p);
            }
        };
    }

    // ------------------------------------------------------------------ vitals

    private void showStats(String s) {
        JSONObject j;
        try { j = new JSONObject(s); } catch (Exception e) {
            heading.setText("laptop not connected");
            return;
        }
        heading.setText(j.optString("host") + "  ·  up " + duration(j.optLong("uptime_s")));
        double cpu = j.optDouble("cpu");
        cpuVal.setText(Math.round(cpu) + "%");
        JSONArray c = j.optJSONArray("cores");
        int busiest = 0;
        if (c != null) {
            int[] v = new int[c.length()];
            for (int i = 0; i < v.length; i++) { v[i] = c.optInt(i); busiest = Math.max(busiest, v[i]); }
            cores.set(v);
            cpuSub.setText(v.length + " threads  ·  peak " + busiest + "%");
        }
        cpuSpark.push((float) cpu / 100f);

        JSONObject g = j.optJSONObject("gpu");
        if (g != null) {
            gpuVal.setText(g.optInt("util") + "%");
            gpuSub.setText(g.optInt("temp") + "°  ·  " + Math.round(g.optDouble("power_w")) + " W  ·  "
                    + gb(g.optLong("mem_used_mb")) + "/" + gb(g.optLong("mem_total_mb")) + " GB");
            gpuSpark.push(g.optInt("util") / 100f);
        } else {
            gpuVal.setText("–");
            gpuSub.setText("no nvidia gpu");
        }

        long used = j.optLong("ram_used_mb"), total = Math.max(1, j.optLong("ram_total_mb"));
        ramVal.setText(Math.round(100.0 * used / total) + "%");
        ramSub.setText(gb(used) + " of " + gb(total) + " GB");
        ramSpark.push((float) used / total);

        StringBuilder f = new StringBuilder();
        JSONObject b = j.optJSONObject("battery");
        if (b != null) {
            f.append("battery ").append(b.optInt("pct")).append("%  ·  ")
                    .append(b.optBoolean("charging") ? "charging" : b.optBoolean("plugged") ? "plugged in" : "on battery");
            int m = b.optInt("minutes", -1);
            if (!b.optBoolean("plugged") && m > 0) f.append(", ").append(m / 60).append(" h ").append(m % 60).append(" min left");
            f.append('\n');
        }
        f.append("network  ↓ ").append(rate(j.optLong("net_down_bps"))).append("   ↑ ").append(rate(j.optLong("net_up_bps")));
        footer.setText(f.toString());
    }

    private static String gb(long mb) {
        return String.format(Locale.US, mb >= 10240 ? "%.0f" : "%.1f", mb / 1024.0);
    }

    private static String rate(long bps) {
        if (bps >= 1_048_576) return String.format(Locale.US, "%.1f MB/s", bps / 1048576.0);
        if (bps >= 1024) return (bps / 1024) + " KB/s";
        return bps + " B/s";
    }

    private static String duration(long s) {
        long d = s / 86400, h = s / 3600 % 24, m = s / 60 % 60;
        return d > 0 ? d + "d " + h + "h" : h > 0 ? h + "h " + m + "m" : m + "m";
    }

    // ------------------------------------------------------------------ controls

    private void showState(String s) {
        if (s == null) return;
        JSONObject j;
        try { j = new JSONObject(s); } catch (Exception e) { return; }
        JSONArray outs = j.optJSONArray("outputs");
        outputs.removeAllViews();
        if (outs != null) for (int i = 0; i < outs.length(); i++) outputs.addView(outputRow(outs.optJSONObject(i)));
        int v = j.optInt("volume", -1);
        if (v >= 0) { volume.setValue(v / 100f); volumePct.setText(v + "%"); }
        muted = j.optBoolean("muted");
        muteGlyph.setLevel(muted ? 0f : -1f);
        JSONObject m = j.optJSONObject("mic");
        if (m != null) mic.set(!m.optBoolean("muted"), m.optBoolean("muted") ? "muted" : "");
        else mic.set(false, "none");
        String b = j.optString("bluetooth");
        bt.set("on".equals(b), "none".equals(b) ? "none" : "");
        int br = j.optInt("brightness", -1);
        if (br >= 0) { brightness.setValue(br / 100f); brightnessPct.setText(br + "%"); }
        setPower(j.optString("power_mode", "balanced"));
    }

    private void setPower(String mode) {
        powerMode = mode;
        power.set(!mode.equals("balanced"), mode.equals("efficiency") ? "efficiency" : mode.equals("performance") ? "performance" : "balanced");
    }

    private View outputRow(final JSONObject o) {
        boolean def = o.optBoolean("default");
        LinearLayout r = row(act);
        View bar = new View(act);
        bar.setBackgroundColor(def ? Metro.COBALT : 0);
        r.addView(bar, new LinearLayout.LayoutParams(Metro.dp(4), Metro.dp(22)));
        // "Headphones (Dubstep Pop 1200/1210)" -> "Dubstep Pop 1200/1210 · headphones"
        String name = o.optString("name");
        int p = name.indexOf(" (");
        String nice = p > 0 && name.endsWith(")") ? name.substring(p + 2, name.length() - 1) + "  ·  " + name.substring(0, p).toLowerCase(Locale.ROOT) : name;
        TextView t = Tile.text(act, nice, 17, Metro.semilight, def ? Metro.TEXT : Metro.TEXT_DIM);
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(0, -2, 1f);
        tl.leftMargin = Metro.dp(10);
        r.addView(t, tl);
        r.setPadding(0, Metro.dp(5), 0, Metro.dp(5));
        r.setOnTouchListener(Metro.TILT);
        r.setOnClickListener(v -> {
            for (int i = 0; i < outputs.getChildCount(); i++) {       // optimistic: highlight the tapped one
                LinearLayout row = (LinearLayout) outputs.getChildAt(i);
                boolean sel = row == v;
                row.getChildAt(0).setBackgroundColor(sel ? Metro.COBALT : 0);
                ((TextView) row.getChildAt(1)).setTextColor(sel ? Metro.TEXT : Metro.TEXT_DIM);
            }
            try { cmd("output?id=" + java.net.URLEncoder.encode(o.optString("id"), "UTF-8")); } catch (Exception ignored) {}
        });
        return r;
    }

    // ------------------------------------------------------------------ helpers / views

    private static LinearLayout vertical(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    private static LinearLayout row(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        return l;
    }

    /** Big number, label, detail line and a sparkline; returns {value, detail}. */
    private static TextView[] meter(Context c, LinearLayout parent, String label, Spark spark) {
        LinearLayout m = vertical(c);
        TextView val = Tile.text(c, "–", 40, Metro.light, Metro.TEXT);
        TextView lab = Tile.text(c, label, 15, Metro.semilight, Metro.TEXT_DIM);
        TextView sub = Tile.text(c, "", 12, Metro.semilight, Metro.TEXT_FAINT);
        sub.setSingleLine(true);
        sub.setEllipsize(TextUtils.TruncateAt.END);
        m.addView(val);
        m.addView(lab);
        m.addView(sub);
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(-1, Metro.dp(34));
        sl.topMargin = Metro.dp(6);
        m.addView(spark, sl);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1f);
        lp.rightMargin = Metro.dp(12);
        parent.addView(m, lp);
        return new TextView[]{val, sub};
    }

    private void toast(String s) { Toast.makeText(act, s, Toast.LENGTH_SHORT).show(); }

    /** Last 60 samples as a line with a faint fill. */
    private static final class Spark extends View {
        private final float[] h = new float[60];
        private int n;
        private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG), area = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path p = new Path();

        Spark(Context c, int color) {
            super(c);
            line.setStyle(Paint.Style.STROKE);
            line.setStrokeWidth(Metro.dp(1.5f));
            line.setColor(color);
            area.setColor((color & 0x00FFFFFF) | 0x40000000);
        }

        void push(float v) {
            System.arraycopy(h, 1, h, 0, h.length - 1);
            h[h.length - 1] = Math.max(0f, Math.min(1f, v));
            n = Math.min(h.length, n + 1);
            invalidate();
        }

        @Override protected void onDraw(Canvas c) {
            if (n < 2) return;
            float w = getWidth(), ht = getHeight(), step = w / (h.length - 1);
            p.reset();
            int first = h.length - n;
            for (int i = first; i < h.length; i++) {
                float x = i * step, y = ht - h[i] * (ht - 2) - 1;
                if (i == first) p.moveTo(x, y); else p.lineTo(x, y);
            }
            c.drawPath(p, line);
            p.lineTo(w, ht);
            p.lineTo(first * step, ht);
            p.close();
            c.drawPath(p, area);
        }
    }

    /** Per-thread load as thin bars. */
    private static final class Cores extends View {
        private int[] v = new int[0];
        private final Paint bar = new Paint(), bg = new Paint();

        Cores(Context c) {
            super(c);
            bar.setColor(Metro.COBALT);
            bg.setColor(0xFF222222);
        }

        void set(int[] vals) { v = vals; invalidate(); }

        @Override protected void onDraw(Canvas c) {
            if (v.length == 0) return;
            float gap = Metro.dp(3), w = (getWidth() - gap * (v.length - 1)) / v.length, ht = getHeight();
            for (int i = 0; i < v.length; i++) {
                float x = i * (w + gap);
                c.drawRect(x, 0, x + w, ht, bg);
                float bh = Math.max(Metro.dp(1), v[i] / 100f * ht);
                c.drawRect(x, ht - bh, x + w, ht, bar);
            }
        }
    }

    /** Small quick tile: accent when on, charcoal when off; glyph top-left, label + detail bottom-left. */
    private static final class Toggle extends FrameLayout {
        boolean on;
        private final TextView sub;

        Toggle(Context c, int glyph, String name) {
            super(c);
            LayoutParams gl = new LayoutParams(Metro.dp(24), Metro.dp(24));
            gl.leftMargin = Metro.dp(8);
            gl.topMargin = Metro.dp(8);
            addView(new Glyph(c, glyph), gl);
            LinearLayout st = new LinearLayout(c);
            st.setOrientation(LinearLayout.VERTICAL);
            TextView label = Tile.text(c, name, 13, Metro.semilight, Metro.TEXT);
            sub = Tile.text(c, "", 11, Metro.semilight, Metro.TEXT_DIM);
            st.addView(label);
            st.addView(sub);
            LayoutParams sl = new LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.START);
            sl.leftMargin = Metro.dp(8);
            sl.bottomMargin = Metro.dp(5);
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
}

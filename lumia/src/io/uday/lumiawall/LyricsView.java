package io.uday.lumiawall;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;
import android.os.SystemClock;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;

/**
 * Time-synced lyrics, drawn directly (no child views) so every frame is one cheap pass:
 *  - the current line glows to full size and brightness, the rest recede; each line eases on its own;
 *  - the list glides to keep the current line at ~38% height (exponential follow, so it never jerks);
 *  - a soft karaoke sweep brightens the current line from left to right while it's being sung;
 *  - drag to browse (auto-follow resumes after a few seconds), tap a line to seek there.
 * Playback time runs on our own clock between the bridge's 1 s polls and is nudged, not snapped, toward them.
 */
final class LyricsView extends View {
    interface OnSeek { void seek(long ms); }

    private static final float FOCUS = 0.38f;          // where the current line sits (fraction of height)
    private static final float DIM_SCALE = 0.86f;
    private static final long FOLLOW_AFTER_DRAG_MS = 4000;

    private final TextPaint paint = new TextPaint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
    private final TextPaint hintPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fade = new Paint();
    private final android.graphics.Rect visible = new android.graphics.Rect();
    private final int tint;
    private OnSeek onSeek;

    // content
    private long[] times = new long[0];
    private String[] texts = new String[0];
    private boolean synced;
    private String message = "";
    private StaticLayout[] layouts = new StaticLayout[0];
    private float[] tops = new float[0];
    private float[] glow = new float[0];               // 0 = receded, 1 = current (animated)
    private float contentH, lineGap;
    private int layoutWidth = -1;

    // playback clock
    private long baseMs, baseAt;
    private boolean playing;

    // scrolling
    private float scroll, velocity;
    private long lastFrame, userUntil;
    private float downX, downY, lastY;
    private boolean dragging;
    private VelocityTracker tracker;
    private final int slop;
    private final Runnable wake = this::invalidate;

    LyricsView(Context c, int accent) {
        super(c);
        // the sweep's leading edge: halfway between the accent and white
        tint = 0xFF000000 | (((accent >> 16 & 0xFF) + 255) / 2 << 16)
                | (((accent >> 8 & 0xFF) + 255) / 2 << 8) | ((accent & 0xFF) + 255) / 2;
        paint.setTypeface(Metro.light);
        paint.setTextSize(Metro.dp(30));
        hintPaint.setTypeface(Metro.semilight);
        hintPaint.setTextSize(Metro.dp(20));
        hintPaint.setColor(Metro.TEXT_FAINT);
        lineGap = Metro.dp(16);
        slop = ViewConfiguration.get(c).getScaledTouchSlop();
    }

    void setOnSeek(OnSeek s) { onSeek = s; }

    /** Synced lines ({@code times} non-empty), plain text, or just a message when {@code lines} is empty. */
    void setLyrics(long[] t, String[] lines, boolean isSynced, String msg) {
        times = t;
        texts = lines;
        synced = isSynced;
        message = msg == null ? "" : msg;
        layoutWidth = -1;                               // rebuild on next draw
        glow = new float[lines.length];
        scroll = isSynced ? -getHeight() * FOCUS : 0;   // synced: line 0 starts at the focus point
        userUntil = 0;
        velocity = 0;
        invalidate();
    }

    /** Called once a second with the bridge's position. Small drift is eased out so the sweep never stutters. */
    void setPosition(long posMs, boolean isPlaying) {
        long now = SystemClock.uptimeMillis();
        long ours = nowMs();
        long err = posMs - ours;
        if (!isPlaying || !playing || Math.abs(err) > 600) { baseMs = posMs; }
        else { baseMs = ours + err / 4; }
        baseAt = now;
        playing = isPlaying;
        invalidate();
    }

    private long nowMs() {
        return playing ? baseMs + (SystemClock.uptimeMillis() - baseAt) : baseMs;
    }

    private int currentLine(long now) {
        int idx = -1;
        for (int i = 0; i < times.length; i++) { if (times[i] <= now) idx = i; else break; }
        return idx;
    }

    private void buildLayouts(int w) {
        layoutWidth = w;
        int n = texts.length;
        layouts = new StaticLayout[n];
        tops = new float[n];
        float y = 0;
        for (int i = 0; i < n; i++) {
            String s = !texts[i].isEmpty() ? texts[i]
                    : synced ? "•  •  •" : " ";           // synced: instrumental break; plain: stanza gap
            layouts[i] = StaticLayout.Builder.obtain(s, 0, s.length(), paint, w)
                    .setAlignment(Layout.Alignment.ALIGN_NORMAL).setIncludePad(false).build();
            tops[i] = y;
            y += layouts[i].getHeight() * (synced ? DIM_SCALE : 1f) + lineGap;
        }
        contentH = y;
        fade.setShader(null);
    }

    @Override protected void onDraw(Canvas c) {
        int w = getWidth(), h = getHeight();
        if (texts.length == 0) {
            c.drawText(message, 0, Metro.dp(40), hintPaint);
            return;
        }
        if (layoutWidth != w) buildLayouts(w);

        long frame = SystemClock.uptimeMillis();
        float dt = lastFrame == 0 ? 16 : Math.min(64, frame - lastFrame);
        lastFrame = frame;
        long now = nowMs();
        int cur = synced ? currentLine(now) : -1;
        boolean animating = false;

        // Each line eases toward its target glow (ENTER-like: quick start, soft landing).
        float k = 1f - (float) Math.exp(-dt / 140f);
        for (int i = 0; i < glow.length; i++) {
            float target = i == cur ? 1f : 0f;
            float d = target - glow[i];
            if (Math.abs(d) > 0.002f) { glow[i] += d * k; animating = true; } else glow[i] = target;
        }

        // Line positions with the current line's extra height (it grows from DIM_SCALE to 1).
        float[] y = new float[texts.length];
        float extra = 0;
        for (int i = 0; i < texts.length; i++) {
            y[i] = tops[i] + extra;
            if (synced) extra += layouts[i].getHeight() * (1f - DIM_SCALE) * glow[i];
        }

        // Follow the current line unless the user is browsing.
        boolean following = synced && frame > userUntil && !dragging;
        if (following) {
            float target = (cur < 0 ? 0 : y[cur] + layouts[cur].getHeight() / 2f) - h * FOCUS;
            float d = target - scroll;
            if (Math.abs(d) > 0.5f) { scroll += d * (1f - (float) Math.exp(-dt / 170f)); animating = true; }
            else scroll = target;
        } else if (!dragging && Math.abs(velocity) > 20) {   // fling after a browse
            scroll += velocity * dt / 1000f;
            velocity *= (float) Math.exp(-dt / 325f);
            animating = true;
        }
        if (!following) scroll = Math.max(synced ? -h * FOCUS : 0, Math.min(contentH - h * (synced ? FOCUS : 0.5f), scroll));

        // Sweep through the current line over roughly how long it takes to sing it.
        float sweep = 0;
        if (cur >= 0) {
            long next = cur + 1 < times.length ? times[cur + 1] : times[cur] + 5000;
            long singMs = Math.min(next - times[cur], 600 + 70L * texts[cur].length());
            sweep = Math.max(0f, Math.min(1f, (now - times[cur]) / (float) Math.max(1, singMs)));
            if (sweep < 1f && playing) animating = true;
        }

        for (int i = 0; i < texts.length; i++) {
            StaticLayout l = layouts[i];
            float top = y[i] - scroll;
            if (top > h || top + l.getHeight() < 0) continue;
            float g = synced ? glow[i] : 0.55f;
            float scale = synced ? DIM_SCALE + (1f - DIM_SCALE) * g : 1f;
            int alpha = (int) (255 * (synced ? (i < cur ? 0.24f : 0.34f) + 0.56f * g : 0.62f));
            c.save();
            c.translate(0, top);
            c.scale(scale, scale);
            paint.setColor(0xFFFFFFFF);
            paint.setAlpha(i == cur ? (int) (alpha * 0.62f) : alpha);
            l.draw(c);
            if (i == cur) drawSwept(c, l, sweep, alpha);
            c.restore();
        }

        // Soft top and bottom edges (the background is black, so painting black gradients is the cheap mask).
        if (fade.getShader() == null) {
            fade.setShader(new LinearGradient(0, 0, 0, h, new int[]{0xFF000000, 0x00000000, 0x00000000, 0xFF000000},
                    new float[]{0f, 0.2f, 0.8f, 1f}, Shader.TileMode.CLAMP));
        }
        c.drawRect(0, 0, w, h, fade);

        // Frame-by-frame only while something moves and we're actually on screen (the panorama keeps
        // off-screen sections drawn); otherwise wake up when the next line starts.
        boolean onScreen = getGlobalVisibleRect(visible);
        if (animating && onScreen) postInvalidateOnAnimation();
        else if (playing) {
            long wait = cur + 1 < times.length ? times[cur + 1] - now : 1000;
            // one pending wake-up at a time: every draw re-arms it, so posting blindly would pile them up
            removeCallbacks(wake);
            postDelayed(wake, onScreen ? Math.max(16, Math.min(1000, wait)) : 1000);
        }
    }

    /**
     * Brightens the first {@code frac} of the line's glyphs across its wrapped rows. The bright text is painted
     * through a gradient, so the moving edge is a soft, accent-tinted glow inside the letters rather than a hard cut.
     */
    private void drawSwept(Canvas c, StaticLayout l, float frac, int alpha) {
        float total = 0;
        for (int r = 0; r < l.getLineCount(); r++) total += l.getLineWidth(r);
        float left = total * frac, soft = Metro.dp(34);
        for (int r = 0; r < l.getLineCount() && left > 0; r++) {
            float rw = l.getLineWidth(r);
            float take = Math.min(rw, left);
            left -= take;
            float edgeX = take < rw ? take + soft * 0.5f : rw + soft;   // finished rows: no visible edge
            Shader sweepShader = new LinearGradient(edgeX - soft, 0, edgeX, 0,
                    new int[]{0xFFFFFFFF, tint, 0x00000000}, new float[]{0f, 0.55f, 1f}, Shader.TileMode.CLAMP);
            c.save();
            c.clipRect(0, l.getLineTop(r), rw + soft, l.getLineBottom(r));
            TextPaint p = l.getPaint();
            p.setShader(sweepShader);
            p.setColor(0xFFFFFFFF);
            p.setAlpha(alpha);
            l.draw(c);
            p.setShader(null);
            c.restore();
        }
    }

    // ---- touch: vertical drag browses (the panorama keeps horizontal swipes), tap seeks ----

    @Override public boolean onTouchEvent(MotionEvent e) {
        if (texts.length == 0) return false;
        if (tracker == null) tracker = VelocityTracker.obtain();
        tracker.addMovement(e);
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = e.getX(); downY = lastY = e.getY();
                dragging = false;
                velocity = 0;
                return true;
            case MotionEvent.ACTION_MOVE: {
                float dy = e.getY() - downY, dx = e.getX() - downX;
                if (!dragging && Math.abs(dy) > slop && Math.abs(dy) > Math.abs(dx)) {
                    dragging = true;
                    getParent().requestDisallowInterceptTouchEvent(true);
                }
                if (dragging) {
                    scroll -= e.getY() - lastY;
                    userUntil = SystemClock.uptimeMillis() + FOLLOW_AFTER_DRAG_MS;
                    invalidate();
                }
                lastY = e.getY();
                return true;
            }
            case MotionEvent.ACTION_UP:
                if (dragging) {
                    tracker.computeCurrentVelocity(1000);
                    velocity = -tracker.getYVelocity();
                    userUntil = SystemClock.uptimeMillis() + FOLLOW_AFTER_DRAG_MS;
                } else {
                    tap(e.getY());
                }
                // fall through
            case MotionEvent.ACTION_CANCEL:
                dragging = false;
                tracker.recycle();
                tracker = null;
                lastFrame = 0;
                invalidate();
                return true;
        }
        return true;
    }

    private void tap(float ty) {
        if (!synced || onSeek == null || layoutWidth < 0) return;
        float extra = 0;
        for (int i = 0; i < texts.length; i++) {
            float top = tops[i] + extra - scroll;
            float hgt = layouts[i].getHeight() * (DIM_SCALE + (1f - DIM_SCALE) * glow[i]);
            extra += layouts[i].getHeight() * (1f - DIM_SCALE) * glow[i];
            if (ty >= top - lineGap / 2 && ty < top + hgt + lineGap / 2) {
                long ms = times[i];
                onSeek.seek(ms);
                // Jump the highlight now; the next poll confirms it.
                baseMs = ms;
                baseAt = SystemClock.uptimeMillis();
                userUntil = 0;
                invalidate();
                return;
            }
        }
    }
}

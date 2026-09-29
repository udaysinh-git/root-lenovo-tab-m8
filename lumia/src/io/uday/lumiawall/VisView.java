package io.uday.lumiawall;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.View;

/** Spectrum bars fed by the laptop's loopback FFT: instant attack, slow release, like a hi-fi meter. */
final class VisView extends View {
    private final float[] target = new float[32];
    private final float[] shown = new float[32];
    private final float[] peak = new float[32];
    private final Paint bar = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint cap = new Paint(Paint.ANTI_ALIAS_FLAG);
    private boolean live;

    VisView(Context c, int color) {
        super(c);
        bar.setColor(color);
        cap.setColor(Metro.TEXT_DIM);
    }

    /** Called from the network thread with 32 levels 0..255. */
    void push(byte[] frame) {
        synchronized (target) {
            for (int i = 0; i < 32 && i < frame.length; i++) target[i] = (frame[i] & 0xff) / 255f;
        }
        if (!live) { live = true; postInvalidateOnAnimation(); }
    }

    void setLive(boolean on) {
        live = on;
        if (on) postInvalidateOnAnimation();
    }

    @Override protected void onDraw(Canvas c) {
        int n = 32;
        float gap = Metro.dp(3);
        float w = (getWidth() - gap * (n - 1)) / n;
        float h = getHeight();
        boolean moving = false;
        synchronized (target) {
            for (int i = 0; i < n; i++) {
                float t = live ? target[i] : 0f;
                shown[i] = t > shown[i] ? t : shown[i] * 0.86f;          // attack instantly, release slowly
                peak[i] = shown[i] > peak[i] ? shown[i] : Math.max(0f, peak[i] - 0.012f);
                if (shown[i] > 0.004f || peak[i] > 0.004f) moving = true;
            }
        }
        for (int i = 0; i < n; i++) {
            float x = i * (w + gap);
            float bh = Math.max(Metro.dp(2), shown[i] * h);
            c.drawRect(x, h - bh, x + w, h, bar);
            float py = h - peak[i] * h - Metro.dp(3);
            c.drawRect(x, py, x + w, py + Metro.dp(2), cap);
        }
        if (live || moving) postInvalidateOnAnimation();                 // keep animating while there's motion
    }
}

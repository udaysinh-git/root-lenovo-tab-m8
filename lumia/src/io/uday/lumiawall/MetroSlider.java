package io.uday.lumiawall;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.MotionEvent;
import android.view.View;

/** Thin WP-style slider: track, accent fill, rectangular thumb. Reports while dragging and once on release. */
final class MetroSlider extends View {
    interface Listener { void onChange(float value, boolean released); }

    float value;
    private Listener listener;
    private boolean dragging;
    private final Paint track = new Paint(), fill = new Paint(), thumb = new Paint();

    MetroSlider(Context c, int accent) {
        super(c);
        track.setColor(0xFF3A3A3A);
        fill.setColor(accent);
        thumb.setColor(Metro.TEXT);
    }

    void setListener(Listener l) { listener = l; }

    /** Programmatic updates are ignored while the finger is on it, so polling never fights the user. */
    void setValue(float v) {
        if (dragging) return;
        value = Math.max(0f, Math.min(1f, v));
        invalidate();
    }

    @Override protected void onDraw(Canvas c) {
        float h = Metro.dp(4), cy = getHeight() / 2f, pad = Metro.dp(5);
        float w = getWidth() - 2 * pad, x = pad + value * w;
        c.drawRect(pad, cy - h / 2, pad + w, cy + h / 2, track);
        c.drawRect(pad, cy - h / 2, x, cy + h / 2, fill);
        c.drawRect(x - Metro.dp(5), cy - Metro.dp(13), x + Metro.dp(5), cy + Metro.dp(13), thumb);
    }

    @Override public boolean onTouchEvent(MotionEvent e) {
        float pad = Metro.dp(5);
        value = Math.max(0f, Math.min(1f, (e.getX() - pad) / (getWidth() - 2 * pad)));
        invalidate();
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                dragging = true;
                getParent().requestDisallowInterceptTouchEvent(true);   // don't let the panorama take the drag
                // fall through
            case MotionEvent.ACTION_MOVE:
                if (listener != null) listener.onChange(value, false);
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                dragging = false;
                if (listener != null) listener.onChange(value, true);
                return true;
        }
        return true;
    }
}

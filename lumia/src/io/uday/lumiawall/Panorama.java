package io.uday.lumiawall;

import android.animation.ValueAnimator;
import android.content.Context;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * WP8 panorama: sections side by side under one oversized title that scrolls slower than the
 * content (parallax). Releasing a swipe settles on the nearest section.
 */
final class Panorama extends HorizontalScrollView {
    final LinearLayout sections;
    private final TextView title;
    private static final float TITLE_SPEED = 0.45f;   // title moves at 45% of content speed
    Runnable onScroll;                                  // e.g. start/stop work for sections coming into view

    Panorama(Context c, String titleText) {
        super(c);
        setHorizontalScrollBarEnabled(false);
        setOverScrollMode(OVER_SCROLL_NEVER);
        setFillViewport(true);

        FrameLayout canvas = new FrameLayout(c);
        title = Tile.text(c, titleText, 104, Metro.light, Metro.TEXT_FAINT);
        title.setSingleLine(true);
        // The giant title moves on every scroll frame: keep it on its own GPU layer so moving it is just
        // a texture translation instead of re-rasterising 104sp text each frame.
        title.setLayerType(LAYER_TYPE_HARDWARE, null);
        FrameLayout.LayoutParams tlp = new FrameLayout.LayoutParams(-2, -2);
        tlp.leftMargin = Metro.dp(10);
        tlp.topMargin = -Metro.dp(14);
        canvas.addView(title, tlp);

        sections = new LinearLayout(c);
        sections.setOrientation(LinearLayout.HORIZONTAL);
        FrameLayout.LayoutParams slp = new FrameLayout.LayoutParams(-2, -1);
        slp.topMargin = Metro.dp(96);
        canvas.addView(sections, slp);

        addView(canvas, new LayoutParams(-2, -1));
    }

    @Override protected void onScrollChanged(int l, int t, int oldl, int oldt) {
        super.onScrollChanged(l, t, oldl, oldt);
        title.setTranslationX(l * (1f - TITLE_SPEED));    // counteract part of the scroll
        if (onScroll != null) onScroll.run();
    }

    // ---- settling: exactly ONE glide per release, on our own curve ----
    // Before, a fling picked the next section and a posted "snap to nearest" then pulled back toward the
    // old one, so the two moves fought. Now ACTION_UP decides once (fling direction or nearest) and a
    // single ValueAnimator glides there with a long WP-style ease-out.

    private ValueAnimator glide;
    private boolean flung;

    @Override public boolean onInterceptTouchEvent(MotionEvent e) {
        if (e.getActionMasked() == MotionEvent.ACTION_DOWN) stopGlide();   // catch it mid-glide
        return super.onInterceptTouchEvent(e);
    }

    @Override public boolean onTouchEvent(MotionEvent e) {
        int a = e.getActionMasked();
        if (a == MotionEvent.ACTION_DOWN) { stopGlide(); flung = false; }
        boolean r = super.onTouchEvent(e);
        if ((a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) && !flung) {
            scrollToSection(nearest());
        }
        return r;
    }

    @Override public void fling(int velocityX) {
        // Called by super during ACTION_UP when the release was fast: move one section that way.
        flung = true;
        float v = velocityX / Metro.density;               // dp/s
        int from = nearestBehind(velocityX);
        scrollToSection(v > 300 ? from + 1 : v < -300 ? from : nearest());
    }

    /** Index of the section whose left edge is at or before the current scroll (for direction-aware flings). */
    private int nearestBehind(int velocityX) {
        int x = getScrollX(), idx = 0;
        for (int i = 0; i < sections.getChildCount(); i++) {
            if (sections.getChildAt(i).getVisibility() == GONE) continue;   // hidden section (e.g. no lyrics)
            if (sections.getChildAt(i).getLeft() <= x + (velocityX > 0 ? 1 : 0)) idx = i;
        }
        return idx;
    }

    private int nearest() {
        int x = getScrollX(), best = 0, bestD = Integer.MAX_VALUE;
        for (int i = 0; i < sections.getChildCount(); i++) {
            if (sections.getChildAt(i).getVisibility() == GONE) continue;
            int d = Math.abs(sections.getChildAt(i).getLeft() - x);
            if (d < bestD) { bestD = d; best = i; }
        }
        return best;
    }

    private void stopGlide() {
        if (glide != null) { glide.cancel(); glide = null; }
    }

    void scrollToSection(int i) {
        if (sections.getChildCount() == 0) return;
        i = Math.max(0, Math.min(sections.getChildCount() - 1, i));
        if (sections.getChildAt(i).getVisibility() == GONE) return;
        int max = Math.max(0, getChildAt(0).getWidth() - getWidth());
        int target = Math.min(max, sections.getChildAt(i).getLeft());
        int start = getScrollX();
        stopGlide();
        if (start == target) return;
        // Longer trips take a little longer, but always feel like one soft deceleration.
        long dur = 360 + Math.min(260, Math.abs(target - start) / 4);
        glide = ValueAnimator.ofInt(start, target);
        glide.setDuration(dur);
        glide.setInterpolator(Metro.ENTER);
        glide.addUpdateListener(an -> scrollTo((Integer) an.getAnimatedValue(), 0));
        glide.start();
    }

    /** Adds a section with a WP-style lowercase header; returns the content area. */
    FrameLayout addSection(String header, int widthDp) {
        Context c = getContext();
        LinearLayout col = new LinearLayout(c);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(Metro.dp(22), 0, Metro.dp(6), 0);
        TextView h = Tile.text(c, header, 34, Metro.light, Metro.TEXT);
        h.setSingleLine(true);
        col.addView(h, new LinearLayout.LayoutParams(-2, -2));
        FrameLayout body = new FrameLayout(c);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(-1, -1);
        blp.topMargin = Metro.dp(14);
        col.addView(body, blp);
        sections.addView(col, new LinearLayout.LayoutParams(Metro.dp(widthDp), -1));
        return body;
    }
}

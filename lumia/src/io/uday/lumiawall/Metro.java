package io.uday.lumiawall;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.content.Context;
import android.graphics.Typeface;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.PathInterpolator;
import java.util.ArrayList;
import java.util.List;

/** Windows Phone 8 design language: palette, type, and the three signature motions (tilt, flip, turnstile). */
final class Metro {
    private Metro() {}

    // Classic WP accents, deepened ~25% so a wall-mounted panel isn't glaring in a dim room.
    static final int COBALT  = 0xFF1C47B0;
    static final int EMERALD = 0xFF1B6E2E;
    static final int CRIMSON = 0xFF7E1733;
    static final int MAUVE   = 0xFF5A4A6E;
    static final int STEEL   = 0xFF46535F;
    static final int TEAL    = 0xFF0B6F6E;
    static final int AMBER   = 0xFF8E5F0E;
    static final int TAUPE   = 0xFF625844;
    static final int VIOLET  = 0xFF4E2A8C;

    static final int TEXT       = 0xE6FFFFFF;   // never pure white
    static final int TEXT_DIM   = 0x99FFFFFF;
    static final int TEXT_FAINT = 0x4DFFFFFF;

    static Typeface light, semilight, regular;

    static void init(Context c) {
        if (light != null) return;
        light = Typeface.createFromAsset(c.getAssets(), "fonts/selawkl.ttf");
        semilight = Typeface.createFromAsset(c.getAssets(), "fonts/selawksl.ttf");
        regular = Typeface.createFromAsset(c.getAssets(), "fonts/selawk.ttf");
    }

    static float density;
    static int dp(float v) { return Math.round(v * density); }

    /** WP easing: fast start, long soft landing. */
    static final PathInterpolator EXIT = new PathInterpolator(0.55f, 0f, 1f, 0.45f);
    static final PathInterpolator ENTER = new PathInterpolator(0.1f, 0.9f, 0.2f, 1f);

    // ---- tilt: a pressed tile leans away from the finger ----

    static final View.OnTouchListener TILT = new View.OnTouchListener() {
        @Override public boolean onTouch(View v, MotionEvent e) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN: {
                    float nx = (e.getX() / v.getWidth()) - 0.5f;    // -0.5 .. 0.5
                    float ny = (e.getY() / v.getHeight()) - 0.5f;
                    v.setPivotX(v.getWidth() / 2f);
                    v.setPivotY(v.getHeight() / 2f);
                    v.animate().rotationY(nx * 14f).rotationX(-ny * 14f)
                            .scaleX(0.97f).scaleY(0.97f).setDuration(90)
                            .setInterpolator(new DecelerateInterpolator()).start();
                    break;
                }
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    v.animate().rotationY(0).rotationX(0).scaleX(1f).scaleY(1f)
                            .setDuration(160).setInterpolator(new DecelerateInterpolator()).start();
                    break;
            }
            return false;   // let clicks and scrolling through
        }
    };

    // ---- live tile flip: rotate to edge-on, swap faces, rotate back ----

    static void flip(final View front, final View back, final boolean toBack, final View tile) {
        tile.setCameraDistance(8000 * density);
        tile.setPivotY(tile.getHeight() / 2f);
        ObjectAnimator out = ObjectAnimator.ofFloat(tile, View.ROTATION_X, 0f, 90f);
        out.setDuration(220);
        out.setInterpolator(new AccelerateInterpolator());
        out.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator a) {
                front.setVisibility(toBack ? View.INVISIBLE : View.VISIBLE);
                back.setVisibility(toBack ? View.VISIBLE : View.INVISIBLE);
                tile.setRotationX(-90f);
                tile.animate().rotationX(0f).setDuration(320).setInterpolator(ENTER).start();
            }
        });
        out.start();
    }

    // ---- turnstile: tiles swing around the screen's left edge, one after another ----

    /** Swing the tiles out (then run {@code after}), or back in when {@code in} is true. */
    static void turnstile(List<View> tiles, boolean in, final Runnable after) {
        if (tiles.isEmpty()) { if (after != null) after.run(); return; }
        List<Animator> anims = new ArrayList<>();
        long maxEnd = 0;
        for (View t : tiles) {
            int[] loc = new int[2];
            t.getLocationOnScreen(loc);
            t.setCameraDistance(10000 * density);
            t.setPivotX(-loc[0]);                    // hinge = left edge of the screen
            t.setPivotY(t.getHeight() / 2f);
            // stagger: top-left first, like the real thing
            long delay = (long) (loc[1] / density * 0.35f + loc[0] / density * 0.12f);
            ObjectAnimator a = in
                    ? ObjectAnimator.ofFloat(t, View.ROTATION_Y, -80f, 0f)
                    : ObjectAnimator.ofFloat(t, View.ROTATION_Y, 0f, 88f);
            ObjectAnimator f = in
                    ? ObjectAnimator.ofFloat(t, View.ALPHA, 0f, 1f)
                    : ObjectAnimator.ofFloat(t, View.ALPHA, 1f, 0f);
            long dur = in ? 380 : 230;
            a.setDuration(dur); f.setDuration(dur);
            a.setStartDelay(delay); f.setStartDelay(delay);
            a.setInterpolator(in ? ENTER : EXIT);
            f.setInterpolator(in ? ENTER : EXIT);
            anims.add(a); anims.add(f);
            maxEnd = Math.max(maxEnd, delay + dur);
        }
        AnimatorSet set = new AnimatorSet();
        set.playTogether(anims);
        if (after != null) {
            set.addListener(new AnimatorListenerAdapter() {
                @Override public void onAnimationEnd(Animator a) { after.run(); }
            });
        }
        set.start();
    }

    static void resetTurnstile(List<View> tiles) {
        for (View t : tiles) { t.setRotationY(0f); t.setAlpha(1f); }
    }
}

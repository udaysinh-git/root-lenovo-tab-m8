package io.uday.lumiawall;

import android.content.Context;
import android.graphics.drawable.ColorDrawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * A WP8 live tile: solid accent square, label bottom-left, optional back face that flips in.
 * The face contents are plain Views so each tile can show whatever it needs.
 */
final class Tile extends FrameLayout {
    final int cols, rows;
    final FrameLayout front, back;
    private final TextView label;
    private boolean showingBack;
    boolean flippable;

    Tile(Context c, int color, int cols, int rows, String labelText) {
        super(c);
        this.cols = cols;
        this.rows = rows;
        setBackground(new ColorDrawable(color));
        setClipChildren(true);
        setOnTouchListener(Metro.TILT);

        front = new FrameLayout(c);
        back = new FrameLayout(c);
        back.setVisibility(INVISIBLE);
        addView(front, new LayoutParams(-1, -1));
        addView(back, new LayoutParams(-1, -1));

        label = text(c, labelText, 13, Metro.semilight, Metro.TEXT);
        LayoutParams lp = new LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.START);
        lp.setMargins(Metro.dp(8), 0, Metro.dp(8), Metro.dp(5));
        addView(label, lp);
        if (cols == 1) label.setVisibility(GONE);   // small tiles are glyph-only, like WP
    }

    void setLabel(String s) { label.setText(s); }

    /** Flip to the other face (no-op for tiles without a back). */
    void flip() {
        if (!flippable || getWidth() == 0) return;
        showingBack = !showingBack;
        Metro.flip(front, back, showingBack, this);
    }

    // ---- small builders used by the activity ----

    static TextView text(Context c, String s, float sp, android.graphics.Typeface tf, int color) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTypeface(tf);
        t.setTextColor(color);
        t.setIncludeFontPadding(false);
        t.setSingleLine(false);
        t.setEllipsize(TextUtils.TruncateAt.END);
        return t;
    }

    /** A glyph centred in a face, slightly above centre to leave room for the label. */
    static View centredGlyph(Context c, FrameLayout face, int kind, float sizeDp) {
        Glyph g = new Glyph(c, kind);
        LayoutParams lp = new LayoutParams(Metro.dp(sizeDp), Metro.dp(sizeDp), Gravity.CENTER);
        lp.bottomMargin = Metro.dp(10);
        face.addView(g, lp);
        return g;
    }

    /** A vertical text stack pinned top-left of a face. */
    static LinearLayout stack(Context c, FrameLayout face) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        LayoutParams lp = new LayoutParams(-1, -2, Gravity.TOP | Gravity.START);
        lp.setMargins(Metro.dp(10), Metro.dp(8), Metro.dp(10), 0);
        face.addView(l, lp);
        return l;
    }
}

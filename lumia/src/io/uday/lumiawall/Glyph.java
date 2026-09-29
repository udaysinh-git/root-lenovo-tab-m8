package io.uday.lumiawall;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.View;

/** Thin monoline glyphs in the spirit of Segoe MDL2, drawn in a 24x24 design space and scaled. */
final class Glyph extends View {
    static final int MONITOR = 0, BOOK = 1, MANGA = 2, CAMERA = 3, SERVER = 4, BATTERY = 5, MUSIC = 6, CLOUD = 7;

    private final int kind;
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private float level = -1f;   // battery fill 0..1, -1 = hide

    Glyph(Context c, int kind) {
        super(c);
        this.kind = kind;
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeJoin(Paint.Join.ROUND);
        p.setColor(Metro.TEXT);
    }

    void setLevel(float l) { level = l; invalidate(); }

    @Override protected void onDraw(Canvas c) {
        float s = Math.min(getWidth(), getHeight()) / 24f;
        c.save();
        c.translate((getWidth() - 24 * s) / 2f, (getHeight() - 24 * s) / 2f);
        c.scale(s, s);
        p.setStrokeWidth(1.3f);
        p.setStyle(Paint.Style.STROKE);
        path.reset();
        switch (kind) {
            case MONITOR:
                c.drawRoundRect(new RectF(2, 4, 22, 17), 1, 1, p);
                c.drawLine(12, 17, 12, 20.5f, p);
                c.drawLine(8, 20.5f, 16, 20.5f, p);
                break;
            case BOOK:
                path.moveTo(12, 6); path.cubicTo(9, 4.5f, 5, 4.5f, 2.5f, 5.5f); path.lineTo(2.5f, 19);
                path.cubicTo(5, 18, 9, 18, 12, 19.5f);
                path.cubicTo(15, 18, 19, 18, 21.5f, 19); path.lineTo(21.5f, 5.5f);
                path.cubicTo(19, 4.5f, 15, 4.5f, 12, 6); path.lineTo(12, 19.5f);
                c.drawPath(path, p);
                break;
            case MANGA:
                c.drawRect(4, 3, 20, 21, p);
                c.drawLine(4, 10, 20, 10, p);
                c.drawLine(12, 10, 12, 21, p);
                c.drawLine(4, 15.5f, 12, 15.5f, p);
                break;
            case CAMERA:
                path.moveTo(3, 8); path.lineTo(7.5f, 8); path.lineTo(9.5f, 5); path.lineTo(14.5f, 5);
                path.lineTo(16.5f, 8); path.lineTo(21, 8); path.lineTo(21, 19); path.lineTo(3, 19); path.close();
                c.drawPath(path, p);
                c.drawCircle(12, 13, 3.6f, p);
                break;
            case SERVER:
                c.drawRoundRect(new RectF(3, 4, 21, 10), 1, 1, p);
                c.drawRoundRect(new RectF(3, 14, 21, 20), 1, 1, p);
                p.setStyle(Paint.Style.FILL);
                c.drawCircle(6.5f, 7, 0.9f, p);
                c.drawCircle(6.5f, 17, 0.9f, p);
                break;
            case BATTERY:
                c.drawRoundRect(new RectF(2, 7, 20, 17), 1.5f, 1.5f, p);
                c.drawLine(21.5f, 10.5f, 21.5f, 13.5f, p);
                if (level >= 0) {
                    p.setStyle(Paint.Style.FILL);
                    c.drawRect(3.6f, 8.6f, 3.6f + 14.8f * Math.min(1f, level), 15.4f, p);
                }
                break;
            case MUSIC:
                path.moveTo(9, 18); path.lineTo(9, 5); path.lineTo(20, 3); path.lineTo(20, 16);
                c.drawPath(path, p);
                c.drawCircle(6.5f, 18, 2.6f, p);
                c.drawCircle(17.5f, 16, 2.6f, p);
                break;
            case CLOUD:
                path.moveTo(7, 18); path.cubicTo(3.5f, 18, 2.5f, 14.5f, 4.5f, 12.5f);
                path.cubicTo(4.5f, 9, 8.5f, 7.5f, 10.5f, 9.5f);
                path.cubicTo(12, 5.5f, 18.5f, 6, 18.5f, 10.8f);
                path.cubicTo(21.5f, 11, 22, 18, 17.5f, 18); path.close();
                c.drawPath(path, p);
                break;
        }
        c.restore();
    }
}

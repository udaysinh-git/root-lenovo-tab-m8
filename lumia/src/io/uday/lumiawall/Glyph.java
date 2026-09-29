package io.uday.lumiawall;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.View;

/** Thin monoline glyphs in the spirit of Segoe MDL2, drawn in a 24x24 design space and scaled. */
final class Glyph extends View {
    static final int MONITOR = 0, BOOK = 1, MANGA = 2, CAMERA = 3, SERVER = 4, BATTERY = 5, MUSIC = 6, CLOUD = 7,
            PREV = 8, PLAY = 9, PAUSE = 10, NEXT = 11, WIFI = 12, BLUETOOTH = 13, MOON = 14, BOLT = 15, POWER = 16,
            SUN = 17, REFRESH = 18;

    private int kindOverride = -1;
    void setKind(int k) { kindOverride = k; invalidate(); }

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
        switch (kindOverride >= 0 ? kindOverride : kind) {
            case PREV:
                c.drawLine(6, 6, 6, 18, p);
                path.moveTo(18, 6); path.lineTo(9, 12); path.lineTo(18, 18); path.close();
                c.drawPath(path, p);
                break;
            case NEXT:
                c.drawLine(18, 6, 18, 18, p);
                path.moveTo(6, 6); path.lineTo(15, 12); path.lineTo(6, 18); path.close();
                c.drawPath(path, p);
                break;
            case PLAY:
                path.moveTo(8, 5); path.lineTo(19, 12); path.lineTo(8, 19); path.close();
                c.drawPath(path, p);
                break;
            case PAUSE:
                c.drawLine(9, 6, 9, 18, p);
                c.drawLine(15, 6, 15, 18, p);
                break;
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
            case WIFI:
                for (float r : new float[]{5f, 9.5f, 14f}) c.drawArc(new RectF(12 - r, 19 - r, 12 + r, 19 + r), 225, 90, false, p);
                p.setStyle(Paint.Style.FILL);
                c.drawCircle(12, 19, 1.2f, p);
                break;
            case BLUETOOTH:
                path.moveTo(6.5f, 7.5f); path.lineTo(17, 16); path.lineTo(12, 20.5f); path.lineTo(12, 3.5f);
                path.lineTo(17, 8); path.lineTo(6.5f, 16.5f);
                c.drawPath(path, p);
                break;
            case MOON:
                path.moveTo(14, 3.5f); path.cubicTo(8, 4.5f, 5, 10, 7.5f, 15.5f);
                path.cubicTo(10, 20.5f, 16.5f, 21.5f, 20.5f, 17.5f);
                path.cubicTo(14, 18.5f, 10.5f, 10, 14, 3.5f);
                c.drawPath(path, p);
                break;
            case BOLT:
                path.moveTo(13.5f, 2.5f); path.lineTo(5, 13.5f); path.lineTo(11.5f, 13.5f); path.lineTo(10.5f, 21.5f);
                path.lineTo(19, 10); path.lineTo(12.5f, 10); path.close();
                c.drawPath(path, p);
                break;
            case POWER:
                c.drawArc(new RectF(4.5f, 5, 19.5f, 20), -55, 290, false, p);
                c.drawLine(12, 3, 12, 11, p);
                break;
            case SUN:
                c.drawCircle(12, 12, 3.8f, p);
                for (int i = 0; i < 8; i++) {
                    double a = Math.PI / 4 * i;
                    c.drawLine(12 + 6.3f * (float) Math.cos(a), 12 + 6.3f * (float) Math.sin(a),
                            12 + 8.8f * (float) Math.cos(a), 12 + 8.8f * (float) Math.sin(a), p);
                }
                break;
            case REFRESH:
                c.drawArc(new RectF(4.5f, 4.5f, 19.5f, 19.5f), -60, 290, false, p);
                path.moveTo(15.2f, 2.8f); path.lineTo(16.3f, 6.9f); path.lineTo(12.2f, 7.9f);
                c.drawPath(path, p);
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

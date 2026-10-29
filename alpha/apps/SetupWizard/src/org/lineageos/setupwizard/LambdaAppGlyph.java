package org.lineageos.setupwizard;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.View;

/** Temporary functional glyphs; production category assets remain a later design task. */
final class LambdaAppGlyph extends View {
    private final int kind;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

    LambdaAppGlyph(Context context, int kind, int color) {
        super(context);
        this.kind = kind;
        paint.setColor(color);
        paint.setStrokeWidth(5);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
        paint.setStyle(Paint.Style.STROKE);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    private void line(Canvas c, float... xy) {
        Path p = new Path();
        p.moveTo(xy[0], xy[1]);
        for (int i = 2; i < xy.length; i += 2) p.lineTo(xy[i], xy[i + 1]);
        c.drawPath(p, paint);
    }
    void setGlyphColor(int color) { paint.setColor(color); invalidate(); }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float scale = Math.min(getWidth(), getHeight()) / 100f;
        canvas.save();
        canvas.translate((getWidth() - 100 * scale) / 2, (getHeight() - 100 * scale) / 2);
        canvas.scale(scale, scale);
        switch (kind) {
            case 0: // Wi-Fi
                for (int radius : new int[]{42, 29, 16}) {
                    canvas.drawArc(new RectF(50-radius, 76-radius, 50+radius, 76+radius), 222, 96, false, paint);
                }
                paint.setStyle(Paint.Style.FILL);
                canvas.drawCircle(50, 76, 4, paint);
                paint.setStyle(Paint.Style.STROKE);
                break;
            case 1: // Bluetooth
                line(canvas, 32, 30, 68, 67, 49, 84, 49, 16, 68, 33, 32, 70);
                break;
            case 2: // USB
                line(canvas, 50, 77, 50, 20);
                line(canvas, 42, 29, 50, 18, 58, 29);
                line(canvas, 50, 66, 29, 52, 29, 40);
                line(canvas, 50, 56, 71, 44, 71, 34);
                canvas.drawCircle(29, 35, 5, paint);
                canvas.drawRect(66, 25, 76, 35, paint);
                canvas.drawCircle(50, 82, 6, paint);
                break;
            case 3: // CAN
                line(canvas, 12, 65, 26, 65, 26, 35, 43, 35, 43, 65, 60, 65, 60, 35, 77, 35, 77, 65, 89, 65);
                break;
            case 4: // Network
                canvas.drawRect(37, 15, 63, 35, paint);
                line(canvas, 50, 35, 50, 50, 25, 50, 25, 65);
                line(canvas, 50, 50, 75, 50, 75, 65);
                canvas.drawRect(14, 65, 36, 83, paint);
                canvas.drawRect(64, 65, 86, 83, paint);
                break;
            case 5: // Radio
                canvas.drawCircle(50, 35, 5, paint);
                line(canvas, 33, 85, 50, 43, 67, 85);
                line(canvas, 39, 72, 61, 72);
                canvas.drawArc(new RectF(27, 12, 73, 58), -45, 90, false, paint);
                canvas.drawArc(new RectF(27, 12, 73, 58), 135, 90, false, paint);
                canvas.drawArc(new RectF(14, -1, 86, 71), -42, 84, false, paint);
                canvas.drawArc(new RectF(14, -1, 86, 71), 138, 84, false, paint);
                break;
            case 6: // Settings
                canvas.drawCircle(50, 50, 23, paint);
                canvas.drawCircle(50, 50, 8, paint);
                for (int angle = 0; angle < 360; angle += 45) {
                    canvas.save();
                    canvas.rotate(angle, 50, 50);
                    line(canvas, 50, 16, 50, 26);
                    canvas.restore();
                }
                break;
            case 7: // Tools
                canvas.drawRoundRect(new RectF(13, 22, 87, 78), 5, 5, paint);
                line(canvas, 35, 22, 35, 13, 65, 13, 65, 22);
                line(canvas, 13, 45, 87, 45);
                line(canvas, 50, 40, 50, 51);
                break;
            case 8: // Session
                canvas.drawRoundRect(new RectF(12, 20, 88, 80), 5, 5, paint);
                line(canvas, 27, 37, 40, 50, 27, 63);
                line(canvas, 49, 63, 69, 63);
                break;
            case 9: // Files
                line(canvas, 13, 78, 13, 22, 38, 22, 46, 33, 87, 33, 87, 78, 13, 78);
                break;
            default:
                line(canvas, 62, 25, 37, 50, 62, 75);
        }
        canvas.restore();
    }
}

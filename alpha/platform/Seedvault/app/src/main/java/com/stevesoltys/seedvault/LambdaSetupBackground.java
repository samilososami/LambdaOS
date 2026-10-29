/* SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 Lambda Project.
 */
package com.stevesoltys.seedvault;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;

/** One resolution-independent background for every Lambda setup page.
 * Approved upper-left graphite gradient; final intensity is 95% of the preview.
 * The fine dot grid is decorative, not a view or an accessibility target.
 */
public final class LambdaSetupBackground extends Drawable {
    public static final float INTENSITY = .95f;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private int alpha = 255;
    private Shader shader;
    private final boolean light;

    public LambdaSetupBackground() { this(false); }
    public LambdaSetupBackground(boolean light) { this.light = light; }

    @Override protected void onBoundsChange(Rect bounds) {
        // Draw in the common 360 x 760 design coordinate system, independent of DPI.
        shader = new RadialGradient(0, 0, 1,
                light ? new int[]{0xffe5e5e5, 0xffeeeeee, 0xfff5f5f5, 0xfff7f7f7}
                      : new int[]{0xff292929, 0xff202020, 0xff101010, 0xff080808},
                new float[]{0, .25f, .62f, 1}, Shader.TileMode.CLAMP);
    }

    @Override public void draw(Canvas canvas) {
        Rect b = getBounds();
        if (b.isEmpty()) return;
        canvas.save();
        canvas.translate(b.left, b.top);
        canvas.scale(b.width() / 360f, b.height() / 760f);
        paint.setShader(null);
        paint.setColor(light ? 0xfff7f7f7 : 0xff080808);
        paint.setAlpha(alpha);
        canvas.drawRect(0, 0, 360, 760, paint);
        canvas.save();
        canvas.scale(410, 465);
        paint.setShader(shader);
        paint.setAlpha(Math.round(alpha * INTENSITY));
        canvas.drawRect(0, 0, 360f / 410, 760f / 465, paint);
        canvas.restore();
        paint.setShader(null);
        paint.setColor(light ? 0xff555555 : 0xffdddddd);
        for (float y = 12; y < 380; y += 12) {
            for (float x = 12; x < 360; x += 12) {
                float radius = (float)Math.hypot(x / 350, y / 340);
                float fade = Math.max(0, 1 - radius);
                // The old subpixel dots disappeared on the physical S10 OLED.
                // Preserve the approved gradient; only make its dot print legible.
                paint.setAlpha(Math.round(alpha * .32f * fade * fade));
                canvas.drawCircle(x, y, .36f, paint);
            }
        }
        canvas.restore();
    }

    @Override public void setAlpha(int value) { alpha = value; invalidateSelf(); }
    @Override public void setColorFilter(ColorFilter value) { paint.setColorFilter(value); invalidateSelf(); }
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
}

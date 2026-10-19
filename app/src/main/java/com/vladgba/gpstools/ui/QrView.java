package com.vladgba.gpstools.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.View;

import com.vladgba.gpstools.qr.QrCode;

/** Draws a {@link QrCode} black on white with the mandatory 4-module quiet zone, square. */
public final class QrView extends View {

    private static final int QUIET_ZONE = 4;
    private final Paint dark = new Paint();
    private QrCode code;

    public QrView(Context context) {
        super(context);
        dark.setColor(0xFF000000);
    }

    public void setCode(QrCode code) {
        this.code = code;
        invalidate();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int w = MeasureSpec.getSize(widthMeasureSpec);
        setMeasuredDimension(w, w);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        canvas.drawColor(0xFFFFFFFF);
        if (code == null) return;
        int modules = code.size + 2 * QUIET_ZONE;
        // Whole pixels per module keep the edges crisp for camera decoders.
        int scale = Math.max(1, Math.min(getWidth(), getHeight()) / modules);
        float offsetX = (getWidth() - scale * modules) / 2f + QUIET_ZONE * scale;
        float offsetY = (getHeight() - scale * modules) / 2f + QUIET_ZONE * scale;
        for (int y = 0; y < code.size; y++) {
            for (int x = 0; x < code.size; x++) {
                if (code.get(x, y)) {
                    float left = offsetX + x * scale;
                    float top = offsetY + y * scale;
                    canvas.drawRect(left, top, left + scale, top + scale, dark);
                }
            }
        }
    }
}

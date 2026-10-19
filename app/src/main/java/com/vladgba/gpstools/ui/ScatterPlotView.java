package com.vladgba.gpstools.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;

import com.vladgba.gpstools.R;
import com.vladgba.gpstools.gnss.PositionAverager;

import java.util.Locale;

/**
 * Horizontal scatter of averaged fixes around their mean (north up), with CEP (50%) and 2DRMS
 * (~95%) circles. The scale adapts to the spread.
 */
public final class ScatterPlotView extends View {

    private final Paint pointPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint gridPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint cepPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint drmsPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint meanPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float dp;
    private PositionAverager averager;

    public ScatterPlotView(Context context) {
        this(context, null);
    }

    public ScatterPlotView(Context context, AttributeSet attrs) {
        super(context, attrs);
        dp = Ui.dp(context, 1);
        pointPaint.setColor(0x704FC3F7);
        gridPaint.setColor(context.getColor(R.color.divider));
        gridPaint.setStyle(Paint.Style.STROKE);
        gridPaint.setStrokeWidth(dp);
        cepPaint.setColor(0xFF66BB6A);
        cepPaint.setStyle(Paint.Style.STROKE);
        cepPaint.setStrokeWidth(2 * dp);
        drmsPaint.setColor(0xFFFFA726);
        drmsPaint.setStyle(Paint.Style.STROKE);
        drmsPaint.setStrokeWidth(2 * dp);
        textPaint.setTextSize(Ui.sp(context, 11));
        textPaint.setColor(context.getColor(R.color.text_secondary));
        meanPaint.setColor(0xFFFFFFFF);
        meanPaint.setStrokeWidth(2 * dp);
    }

    public void setAverager(PositionAverager averager) {
        this.averager = averager;
        invalidate();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int w = MeasureSpec.getSize(widthMeasureSpec);
        setMeasuredDimension(w, Math.min(w, Ui.dp(getContext(), 360)));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float cx = getWidth() / 2f;
        float cy = getHeight() / 2f;
        float radius = Math.min(cx, cy) - 16 * dp;
        canvas.drawCircle(cx, cy, radius, gridPaint);
        canvas.drawLine(cx - radius, cy, cx + radius, cy, gridPaint);
        canvas.drawLine(cx, cy - radius, cx, cy + radius, gridPaint);
        textPaint.setTextAlign(Paint.Align.CENTER);
        canvas.drawText("N", cx, cy - radius - 4 * dp, textPaint);
        if (averager == null || averager.getCount() == 0) return;

        double drms2 = averager.get2Drms();
        double cep = averager.getCep50();
        double meanE = averager.getMeanEastOffset();
        double meanN = averager.getMeanNorthOffset();
        double extent = Double.isNaN(drms2) ? 1 : Math.max(1, drms2 * 1.4);
        float scale = (float) (radius / extent);

        for (int i = 0; i < averager.getPointCount(); i++) {
            float x = cx + (float) ((averager.pointEast(i) - meanE) * scale);
            float y = cy - (float) ((averager.pointNorth(i) - meanN) * scale);
            if (Math.hypot(x - cx, y - cy) > radius) continue;
            canvas.drawCircle(x, y, 2 * dp, pointPaint);
        }
        if (!Double.isNaN(cep)) canvas.drawCircle(cx, cy, (float) (cep * scale), cepPaint);
        if (!Double.isNaN(drms2)) canvas.drawCircle(cx, cy, (float) (drms2 * scale), drmsPaint);
        canvas.drawLine(cx - 8 * dp, cy, cx + 8 * dp, cy, meanPaint);
        canvas.drawLine(cx, cy - 8 * dp, cx, cy + 8 * dp, meanPaint);

        textPaint.setTextAlign(Paint.Align.LEFT);
        canvas.drawText(String.format(Locale.ROOT, "edge ± %.1f m", extent), 4 * dp, getHeight() - 4 * dp, textPaint);
    }
}

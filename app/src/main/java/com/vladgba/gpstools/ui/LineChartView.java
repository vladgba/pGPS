package com.vladgba.gpstools.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.View;

import com.vladgba.gpstools.R;
import com.vladgba.gpstools.gnss.RingSeries;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Time-series line chart over a sliding window ending now: title and legend on top, Y grid with
 * labels, gaps where values are NaN. Y range is fixed or auto-scaled to the visible data.
 */
public final class LineChartView extends View {

    public static final class Series {
        final RingSeries data;
        final int color;
        final String label;

        public Series(RingSeries data, int color, String label) {
            this.data = data;
            this.color = color;
            this.label = label;
        }
    }

    private final List<Series> series = new ArrayList<>();
    private final Paint gridPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint axisText = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint titlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint legendPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final float dp;

    private String title = "";
    private long windowMs = 10 * 60_000L;
    private float fixedMin = Float.NaN;
    private float fixedMax = Float.NaN;
    private float minSpan = 1f;
    private boolean includeZero = true;

    public LineChartView(Context context) {
        this(context, null);
    }

    public LineChartView(Context context, AttributeSet attrs) {
        super(context, attrs);
        dp = Ui.dp(context, 1);
        gridPaint.setColor(context.getColor(R.color.divider));
        gridPaint.setStrokeWidth(dp);
        axisText.setColor(context.getColor(R.color.text_secondary));
        axisText.setTextSize(Ui.sp(context, 9));
        axisText.setTextAlign(Paint.Align.RIGHT);
        titlePaint.setColor(context.getColor(R.color.text_primary));
        titlePaint.setTextSize(Ui.sp(context, 13));
        titlePaint.setFakeBoldText(true);
        legendPaint.setTextSize(Ui.sp(context, 11));
        linePaint.setStyle(Paint.Style.STROKE);
        linePaint.setStrokeWidth(2 * dp);
        linePaint.setStrokeJoin(Paint.Join.ROUND);
    }

    public LineChartView setTitle(String title) {
        this.title = title;
        return this;
    }

    public LineChartView addSeries(RingSeries data, int color, String label) {
        series.add(new Series(data, color, label));
        return this;
    }

    public void clearSeries() {
        series.clear();
    }

    /** Fixed Y range; pass NaN for auto. */
    public LineChartView setRange(float min, float max) {
        fixedMin = min;
        fixedMax = max;
        return this;
    }

    public LineChartView setAutoRange(float minSpan, boolean includeZero) {
        fixedMin = fixedMax = Float.NaN;
        this.minSpan = minSpan;
        this.includeZero = includeZero;
        return this;
    }

    public void setWindow(long windowMs) {
        this.windowMs = windowMs;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        long now = SystemClock.elapsedRealtime();
        float titleHeight = titlePaint.getFontSpacing();
        float left = getPaddingLeft() + 34 * dp;
        float right = getWidth() - getPaddingRight() - 8 * dp;
        float top = getPaddingTop() + titleHeight + 8 * dp;
        float bottom = getHeight() - getPaddingBottom() - 16 * dp;
        if (right <= left || bottom <= top) return;

        // Title and legend.
        canvas.drawText(title, getPaddingLeft(), getPaddingTop() - titlePaint.ascent(), titlePaint);
        float lx = getPaddingLeft() + titlePaint.measureText(title) + 14 * dp;
        for (Series s : series) {
            legendPaint.setColor(s.color);
            String label = "━ " + s.label;
            if (lx + legendPaint.measureText(label) > getWidth()) break;
            canvas.drawText(label, lx, getPaddingTop() - titlePaint.ascent(), legendPaint);
            lx += legendPaint.measureText(label) + 12 * dp;
        }

        // Y range.
        float min = fixedMin;
        float max = fixedMax;
        if (Float.isNaN(min) || Float.isNaN(max)) {
            min = Float.POSITIVE_INFINITY;
            max = Float.NEGATIVE_INFINITY;
            for (Series s : series) {
                for (int i = 0; i < s.data.size(); i++) {
                    if (now - s.data.time(i) > windowMs) continue;
                    float v = s.data.value(i);
                    if (Float.isNaN(v)) continue;
                    min = Math.min(min, v);
                    max = Math.max(max, v);
                }
            }
            if (min == Float.POSITIVE_INFINITY) {
                min = 0;
                max = minSpan;
            }
            if (includeZero) min = Math.min(0, min);
            if (max - min < minSpan) max = min + minSpan;
            float step = niceStep((max - min) / 4f);
            min = (float) Math.floor(min / step) * step;
            max = (float) Math.ceil(max / step) * step;
        }

        // Grid.
        float step = niceStep((max - min) / 4f);
        float textCenter = -(axisText.descent() + axisText.ascent()) / 2f;
        for (float v = min; v <= max + step * 0.01f; v += step) {
            float y = bottom - (bottom - top) * (v - min) / (max - min);
            canvas.drawLine(left, y, right, y, gridPaint);
            canvas.drawText(formatValue(v, step), left - 4 * dp, y + textCenter, axisText);
        }
        axisText.setTextAlign(Paint.Align.LEFT);
        canvas.drawText(String.format(Locale.ROOT, "−%d min", windowMs / 60_000), left, bottom + 13 * dp, axisText);
        axisText.setTextAlign(Paint.Align.RIGHT);
        canvas.drawText("now", right, bottom + 13 * dp, axisText);

        // Lines.
        for (Series s : series) {
            path.reset();
            boolean pen = false;
            for (int i = 0; i < s.data.size(); i++) {
                long age = now - s.data.time(i);
                if (age > windowMs) continue;
                float v = s.data.value(i);
                if (Float.isNaN(v)) {
                    pen = false;
                    continue;
                }
                float x = right - (right - left) * age / (float) windowMs;
                float y = bottom - (bottom - top) * (Math.max(min, Math.min(max, v)) - min) / (max - min);
                if (pen) path.lineTo(x, y);
                else path.moveTo(x, y);
                pen = true;
            }
            linePaint.setColor(s.color);
            canvas.drawPath(path, linePaint);
        }
    }

    private static float niceStep(float raw) {
        if (raw <= 0) return 1;
        double exp = Math.pow(10, Math.floor(Math.log10(raw)));
        double f = raw / exp;
        double nice = f <= 1 ? 1 : f <= 2 ? 2 : f <= 5 ? 5 : 10;
        return (float) (nice * exp);
    }

    private static String formatValue(float v, float step) {
        return step >= 1 ? String.format(Locale.ROOT, "%.0f", v) : String.format(Locale.ROOT, "%.1f", v);
    }
}

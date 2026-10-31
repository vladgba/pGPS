package x.vladgba.gps.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.AttributeSet;
import android.view.View;

import x.vladgba.gps.R;
import x.vladgba.gps.gnss.Constellations;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Satellites above the mask per constellation over the coming hours (from orbit predictions). */
public final class ForecastView extends View {

    private final Paint gridPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint axisText = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint legendPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final SimpleDateFormat time = new SimpleDateFormat("HH:mm", Locale.getDefault());
    private final float dp;
    private int[][] counts;
    private long startMs;
    private long stepMs;

    public ForecastView(Context context) {
        this(context, null);
    }

    public ForecastView(Context context, AttributeSet attrs) {
        super(context, attrs);
        dp = Ui.dp(context, 1);
        gridPaint.setColor(context.getColor(R.color.divider));
        gridPaint.setStrokeWidth(dp);
        axisText.setColor(context.getColor(R.color.text_secondary));
        axisText.setTextSize(Ui.sp(context, 9));
        linePaint.setStyle(Paint.Style.STROKE);
        linePaint.setStrokeWidth(2 * dp);
        legendPaint.setTextSize(Ui.sp(context, 11));
    }

    /** @param counts [step][constellation type] satellites above the mask */
    public void setForecast(int[][] counts, long startMs, long stepMs) {
        this.counts = counts;
        this.startMs = startMs;
        this.stepMs = stepMs;
        // Derive everything onDraw needs here, so drawing allocates nothing.
        int n = counts.length;
        totals = new int[n];
        perConstellation = new int[8][n];
        present = new boolean[8];
        maxCount = 4;
        for (int i = 0; i < n; i++) {
            for (int c = 0; c < counts[i].length && c < 8; c++) {
                totals[i] += counts[i][c];
                perConstellation[c][i] = counts[i][c];
                present[c] |= counts[i][c] > 0;
            }
            maxCount = Math.max(maxCount, totals[i]);
        }
        labels = new String[n];
        for (int i = 0; i < n; i++) labels[i] = time.format(new Date(startMs + i * stepMs));
        invalidate();
    }

    private int[] totals;
    private int[][] perConstellation;
    private boolean[] present;
    private String[] labels;
    private int maxCount;

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (counts == null || counts.length < 2) return;
        float legendHeight = legendPaint.getFontSpacing() + 4 * dp;
        float left = 28 * dp;
        float right = getWidth() - 8 * dp;
        float top = legendHeight + 6 * dp;
        float bottom = getHeight() - 16 * dp;

        int max = maxCount;
        int step = max <= 10 ? 2 : max <= 25 ? 5 : 10;
        max = (max + step - 1) / step * step;

        axisText.setTextAlign(Paint.Align.RIGHT);
        float textCenter = -(axisText.descent() + axisText.ascent()) / 2f;
        for (int v = 0; v <= max; v += step) {
            float y = bottom - (bottom - top) * v / max;
            canvas.drawLine(left, y, right, y, gridPaint);
            canvas.drawText(String.valueOf(v), left - 4 * dp, y + textCenter, axisText);
        }
        axisText.setTextAlign(Paint.Align.CENTER);
        int n = counts.length;
        for (int i = 0; i < n; i += Math.max(1, (n - 1) / 4)) {
            float x = left + (right - left) * i / (n - 1);
            canvas.drawText(labels[i], x, bottom + 12 * dp, axisText);
        }

        float lx = left;
        float ly = -legendPaint.ascent();
        drawSeries(canvas, totals, getContext().getColor(R.color.text_primary), left, right, top, bottom, max);
        legendPaint.setColor(getContext().getColor(R.color.text_primary));
        String totalLabel = "━ all";
        canvas.drawText(totalLabel, lx, ly, legendPaint);
        lx += legendPaint.measureText(totalLabel) + 10 * dp;
        for (int c : Constellations.ORDER) {
            if (c >= 8 || !present[c]) continue;
            int color = Constellations.color(c);
            drawSeries(canvas, perConstellation[c], color, left, right, top, bottom, max);
            legendPaint.setColor(color);
            String label = "━ " + Constellations.name(c);
            if (lx + legendPaint.measureText(label) < getWidth()) {
                canvas.drawText(label, lx, ly, legendPaint);
                lx += legendPaint.measureText(label) + 10 * dp;
            }
        }
    }

    private void drawSeries(Canvas canvas, int[] values, int color, float left, float right,
                            float top, float bottom, int max) {
        path.reset();
        for (int i = 0; i < values.length; i++) {
            float x = left + (right - left) * i / (values.length - 1);
            float y = bottom - (bottom - top) * values[i] / max;
            if (i == 0) path.moveTo(x, y);
            else path.lineTo(x, y);
        }
        linePaint.setColor(color);
        canvas.drawPath(path, linePaint);
    }
}

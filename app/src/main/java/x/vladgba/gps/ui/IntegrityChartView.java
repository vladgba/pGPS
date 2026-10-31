package x.vladgba.gps.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.View;

import x.vladgba.gps.R;
import x.vladgba.gps.gnss.TimeSeries;

import java.util.Locale;

/**
 * Two series over the last minutes: top-4 C/N0 on a fixed 0..55 dB-Hz left axis, AGC on an
 * auto-scaled right axis. A jammer shows as both lines falling together.
 */
public final class IntegrityChartView extends View {

    public static final int CN0_COLOR = 0xFF4FC3F7;
    public static final int AGC_COLOR = 0xFFFFA726;
    private static final float CN0_MAX = 55f;

    private final Paint gridPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint leftText = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint rightText = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final float axisWidth;

    private TimeSeries cn0;
    private TimeSeries agc;

    public IntegrityChartView(Context context) {
        this(context, null);
    }

    public IntegrityChartView(Context context, AttributeSet attrs) {
        super(context, attrs);
        axisWidth = Ui.dp(context, 30);
        gridPaint.setColor(context.getColor(R.color.divider));
        gridPaint.setStrokeWidth(Ui.dp(context, 1));
        linePaint.setStyle(Paint.Style.STROKE);
        linePaint.setStrokeWidth(Ui.dp(context, 2));
        linePaint.setStrokeJoin(Paint.Join.ROUND);
        leftText.setTextSize(Ui.sp(context, 9));
        leftText.setColor(CN0_COLOR);
        leftText.setTextAlign(Paint.Align.RIGHT);
        rightText.setTextSize(Ui.sp(context, 9));
        rightText.setColor(AGC_COLOR);
        rightText.setTextAlign(Paint.Align.LEFT);
    }

    public void setSeries(TimeSeries cn0, TimeSeries agc) {
        this.cn0 = cn0;
        this.agc = agc;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float left = getPaddingLeft() + axisWidth;
        float right = getWidth() - getPaddingRight() - axisWidth;
        float top = getPaddingTop() + Ui.dp(getContext(), 6);
        float bottom = getHeight() - getPaddingBottom() - Ui.dp(getContext(), 6);
        if (right <= left || bottom <= top || cn0 == null) return;
        float textCenter = -(leftText.descent() + leftText.ascent()) / 2f;

        for (int db = 0; db <= 50; db += 10) {
            float y = bottom - (bottom - top) * db / CN0_MAX;
            canvas.drawLine(left, y, right, y, gridPaint);
            canvas.drawText(String.valueOf(db), left - Ui.dp(getContext(), 4), y + textCenter, leftText);
        }

        long now = SystemClock.elapsedRealtime();
        long window = cn0.getWindowMs();
        drawSeries(canvas, cn0, 0f, CN0_MAX, CN0_COLOR, now, window, left, right, top, bottom);

        if (agc != null && !agc.isEmpty()) {
            double min = Double.POSITIVE_INFINITY;
            double max = Double.NEGATIVE_INFINITY;
            for (double[] s : agc.samples()) {
                min = Math.min(min, s[1]);
                max = Math.max(max, s[1]);
            }
            // At least a 10 dB span so normal noise does not look like a cliff.
            double mid = (min + max) / 2;
            double half = Math.max(5, (max - min) / 2 + 1);
            float lo = (float) (mid - half);
            float hi = (float) (mid + half);
            drawSeries(canvas, agc, lo, hi, AGC_COLOR, now, window, left, right, top, bottom);
            canvas.drawText(String.format(Locale.ROOT, "%.0f", hi), right + Ui.dp(getContext(), 4), top + textCenter, rightText);
            canvas.drawText(String.format(Locale.ROOT, "%.0f", lo), right + Ui.dp(getContext(), 4), bottom + textCenter, rightText);
        }
    }

    private void drawSeries(Canvas canvas, TimeSeries series, float lo, float hi, int color, long now,
                            long window, float left, float right, float top, float bottom) {
        path.reset();
        boolean first = true;
        for (double[] s : series.samples()) {
            float x = right - (right - left) * (now - (long) s[0]) / (float) window;
            float v = (float) Math.max(lo, Math.min(hi, s[1]));
            float y = bottom - (bottom - top) * (v - lo) / (hi - lo);
            if (first) path.moveTo(x, y);
            else path.lineTo(x, y);
            first = false;
        }
        linePaint.setColor(color);
        canvas.drawPath(path, linePaint);
    }
}

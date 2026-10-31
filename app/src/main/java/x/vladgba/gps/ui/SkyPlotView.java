package x.vladgba.gps.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.view.View;

import x.vladgba.gps.R;
import x.vladgba.gps.gnss.Constellations;
import x.vladgba.gps.gnss.SatelliteInfo;

import java.util.ArrayList;
import java.util.List;

/**
 * Polar sky plot: center is the zenith, the outer ring the horizon, north up. Filled markers
 * are satellites used in the fix, hollow ones are only tracked.
 */
public final class SkyPlotView extends View {

    private final Paint gridPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint axisLabelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint elevationLabelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint markerFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint markerStroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint markerText = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint emptyText = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final int backgroundColor;
    private final float markerRadius;
    private final float labelMargin;

    /** One marker per satellite (signals merged), unused first so used ones draw on top. */
    private final List<SatelliteInfo> markers = new ArrayList<>();
    private String emptyMessage = "";

    public SkyPlotView(Context context) {
        this(context, null);
    }

    public SkyPlotView(Context context, AttributeSet attrs) {
        super(context, attrs);
        backgroundColor = context.getColor(R.color.bg);
        markerRadius = Ui.dp(context, 12);
        labelMargin = Ui.dp(context, 22);

        gridPaint.setStyle(Paint.Style.STROKE);
        gridPaint.setStrokeWidth(Ui.dp(context, 1));
        gridPaint.setColor(context.getColor(R.color.divider));

        axisLabelPaint.setColor(context.getColor(R.color.text_primary));
        axisLabelPaint.setTextSize(Ui.sp(context, 14));
        axisLabelPaint.setTypeface(Typeface.DEFAULT_BOLD);
        axisLabelPaint.setTextAlign(Paint.Align.CENTER);

        elevationLabelPaint.setColor(context.getColor(R.color.text_secondary));
        elevationLabelPaint.setTextSize(Ui.sp(context, 10));

        markerStroke.setStyle(Paint.Style.STROKE);
        markerStroke.setStrokeWidth(Ui.dp(context, 2));

        markerText.setTextSize(Ui.sp(context, 9));
        markerText.setTypeface(Typeface.DEFAULT_BOLD);
        markerText.setTextAlign(Paint.Align.CENTER);

        emptyText.setColor(context.getColor(R.color.text_secondary));
        emptyText.setTextSize(Ui.sp(context, 14));
        emptyText.setTextAlign(Paint.Align.CENTER);
    }

    public void setSatellites(List<SatelliteInfo> satellites, String messageWhenEmpty) {
        markers.clear();
        markers.addAll(SatelliteMarkers.merge(satellites));
        emptyMessage = messageWhenEmpty;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float w = getWidth() - getPaddingLeft() - getPaddingRight();
        float h = getHeight() - getPaddingTop() - getPaddingBottom();
        float cx = getPaddingLeft() + w / 2f;
        float cy = getPaddingTop() + h / 2f;
        float radius = Math.min(w, h) / 2f - labelMargin;
        if (radius <= markerRadius) return;

        drawGrid(canvas, cx, cy, radius);

        if (markers.isEmpty()) {
            canvas.drawText(emptyMessage, cx, cy - (emptyText.descent() + emptyText.ascent()) / 2f, emptyText);
            return;
        }
        float textOffset = -(markerText.descent() + markerText.ascent()) / 2f;
        for (SatelliteInfo s : markers) {
            float r = radius * (90f - clamp(s.elevation, 0f, 90f)) / 90f;
            double az = Math.toRadians(s.azimuth);
            float x = cx + (float) (r * Math.sin(az));
            float y = cy - (float) (r * Math.cos(az));
            int color = Constellations.color(s.constellation);
            int alpha = s.cn0 >= 20f ? 255 : 150;

            if (s.usedInFix) {
                markerFill.setColor(color);
                markerFill.setAlpha(alpha);
                canvas.drawCircle(x, y, markerRadius, markerFill);
                markerText.setColor(0xFF000000);
            } else {
                markerFill.setColor(backgroundColor);
                canvas.drawCircle(x, y, markerRadius, markerFill);
                markerStroke.setColor(color);
                markerStroke.setAlpha(alpha);
                canvas.drawCircle(x, y, markerRadius - markerStroke.getStrokeWidth() / 2f, markerStroke);
                markerText.setColor(color);
            }
            markerText.setAlpha(alpha);
            canvas.drawText(s.rinexId(), x, y + textOffset, markerText);
        }
    }

    private void drawGrid(Canvas canvas, float cx, float cy, float radius) {
        // Rings at 0°, 30° and 60° elevation; labels sit just inside each ring, right of the
        // north axis, so they never collide with the "N" drawn outside the horizon ring.
        float labelX = cx + Ui.dp(getContext(), 4);
        float labelDrop = -elevationLabelPaint.ascent() + Ui.dp(getContext(), 2);
        for (int elevation = 0; elevation < 90; elevation += 30) {
            float r = radius * (90 - elevation) / 90f;
            canvas.drawCircle(cx, cy, r, gridPaint);
            canvas.drawText(elevation + "°", labelX, cy - r + labelDrop, elevationLabelPaint);
        }
        canvas.drawLine(cx - radius, cy, cx + radius, cy, gridPaint);
        canvas.drawLine(cx, cy - radius, cx, cy + radius, gridPaint);
        float d = (float) (radius * Math.sqrt(0.5));
        gridPaint.setAlpha(110);
        canvas.drawLine(cx - d, cy - d, cx + d, cy + d, gridPaint);
        canvas.drawLine(cx - d, cy + d, cx + d, cy - d, gridPaint);
        gridPaint.setAlpha(255);

        float textCenter = -(axisLabelPaint.descent() + axisLabelPaint.ascent()) / 2f;
        float gap = labelMargin / 2f;
        canvas.drawText("N", cx, cy - radius - gap + textCenter, axisLabelPaint);
        canvas.drawText("S", cx, cy + radius + gap + textCenter, axisLabelPaint);
        canvas.drawText("E", cx + radius + gap, cy + textCenter, axisLabelPaint);
        canvas.drawText("W", cx - radius - gap, cy + textCenter, axisLabelPaint);
    }

    private static float clamp(float v, float min, float max) {
        return Math.max(min, Math.min(max, v));
    }
}

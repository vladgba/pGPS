package com.vladgba.gpstools.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;

import com.vladgba.gpstools.R;
import com.vladgba.gpstools.gnss.Constellations;
import com.vladgba.gpstools.gnss.SatelliteInfo;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** C/N0 bar chart, one bar per signal, colored by constellation; faded when not used. */
public final class SignalBarsView extends View {

    private static final float MAX_CN0 = 55f;

    private final Paint barPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint gridPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint axisText = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint barText = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float axisWidth;
    private final float labelHeight;
    private final float minLabelBarWidth;
    private List<SatelliteInfo> bars = Collections.emptyList();

    public SignalBarsView(Context context) {
        this(context, null);
    }

    public SignalBarsView(Context context, AttributeSet attrs) {
        super(context, attrs);
        axisWidth = Ui.dp(context, 26);
        labelHeight = Ui.dp(context, 14);
        minLabelBarWidth = Ui.dp(context, 13);

        gridPaint.setColor(context.getColor(R.color.divider));
        gridPaint.setStrokeWidth(Ui.dp(context, 1));

        axisText.setColor(context.getColor(R.color.text_secondary));
        axisText.setTextSize(Ui.sp(context, 9));
        axisText.setTextAlign(Paint.Align.RIGHT);

        barText.setColor(context.getColor(R.color.text_secondary));
        barText.setTextSize(Ui.sp(context, 8));
        barText.setTextAlign(Paint.Align.CENTER);
    }

    public void setSatellites(List<SatelliteInfo> satellites) {
        List<SatelliteInfo> sorted = new ArrayList<>(satellites);
        Collections.sort(sorted, SatelliteInfo.BY_SYSTEM);
        bars = sorted;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float left = getPaddingLeft() + axisWidth;
        float right = getWidth() - getPaddingRight();
        float top = getPaddingTop() + Ui.dp(getContext(), 4);
        float bottom = getHeight() - getPaddingBottom() - labelHeight;
        float plotHeight = bottom - top;
        if (plotHeight <= 0 || right <= left) return;

        for (int db = 0; db <= 50; db += 10) {
            float y = bottom - plotHeight * db / MAX_CN0;
            canvas.drawLine(left, y, right, y, gridPaint);
            canvas.drawText(String.valueOf(db), left - Ui.dp(getContext(), 4),
                    y - (axisText.descent() + axisText.ascent()) / 2f, axisText);
        }
        if (bars.isEmpty()) return;

        float slot = (right - left) / bars.size();
        float gap = Math.min(slot * 0.2f, Ui.dp(getContext(), 4));
        boolean labels = slot >= minLabelBarWidth;
        for (int i = 0; i < bars.size(); i++) {
            SatelliteInfo s = bars.get(i);
            float x0 = left + i * slot + gap / 2f;
            float x1 = x0 + slot - gap;
            float value = Math.max(0f, Math.min(MAX_CN0, s.cn0));
            float y = bottom - plotHeight * value / MAX_CN0;
            barPaint.setColor(Constellations.color(s.constellation));
            barPaint.setAlpha(s.usedInFix ? 255 : 90);
            canvas.drawRect(x0, y, x1, bottom, barPaint);
            if (labels) {
                canvas.drawText(String.valueOf(s.svid), (x0 + x1) / 2f,
                        bottom + labelHeight - barText.descent(), barText);
            }
        }
    }
}

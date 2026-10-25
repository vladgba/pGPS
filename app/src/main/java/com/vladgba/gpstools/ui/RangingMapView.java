package com.vladgba.gpstools.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.view.View;

import com.vladgba.gpstools.R;
import com.vladgba.gpstools.nav.Geodesic;

import java.util.List;
import java.util.Locale;

/**
 * North-up local metric map for the Range & Triangulation tool:
 * draws observer/station points, bearing rays, connecting lines, and projected/intersected targets.
 */
public final class RangingMapView extends View {

    private final Paint borderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint gridPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint rayPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stationPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint targetPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint errorCirclePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float dp;

    private List<Geodesic.Ray> rays;
    private double p1Lat = Double.NaN;
    private double p1Lon;
    private String p1Label;
    private double p2Lat = Double.NaN;
    private double p2Lon;
    private String p2Label;
    private double targetLat = Double.NaN;
    private double targetLon;
    private String targetLabel;
    private double targetSpreadM;
    private double meLat = Double.NaN;
    private double meLon;

    public RangingMapView(Context context) {
        super(context);
        dp = Ui.dp(context, 1);

        borderPaint.setStyle(Paint.Style.STROKE);
        borderPaint.setStrokeWidth(dp);
        borderPaint.setColor(context.getColor(R.color.divider));

        gridPaint.setStyle(Paint.Style.STROKE);
        gridPaint.setStrokeWidth(dp);
        gridPaint.setColor(0x22FFFFFF);

        rayPaint.setStyle(Paint.Style.STROKE);
        rayPaint.setStrokeWidth(2f * dp);
        rayPaint.setColor(0xFFFFA726);
        rayPaint.setPathEffect(new DashPathEffect(new float[]{6 * dp, 4 * dp}, 0));

        linePaint.setStyle(Paint.Style.STROKE);
        linePaint.setStrokeWidth(2.5f * dp);
        linePaint.setColor(0xFF4FC3F7);

        mePaint.setColor(0xFF4FC3F7);
        stationPaint.setColor(0xFF66BB6A);
        targetPaint.setColor(0xFFFF4081);

        errorCirclePaint.setStyle(Paint.Style.STROKE);
        errorCirclePaint.setStrokeWidth(1.5f * dp);
        errorCirclePaint.setColor(0x88FF4081);

        textPaint.setTextSize(Ui.sp(context, 11));
        textPaint.setColor(context.getColor(R.color.text_secondary));
    }

    /** Configures the map for flash-to-bang or two-point measurement. */
    public void setTwoPointData(double lat1, double lon1, String label1,
                                double lat2, double lon2, String label2,
                                boolean secondIsTarget) {
        this.rays = null;
        this.meLat = Double.NaN;
        this.p1Lat = lat1;
        this.p1Lon = lon1;
        this.p1Label = label1;
        if (secondIsTarget) {
            this.p2Lat = Double.NaN;
            this.targetLat = lat2;
            this.targetLon = lon2;
            this.targetLabel = label2;
        } else {
            this.p2Lat = lat2;
            this.p2Lon = lon2;
            this.p2Label = label2;
            this.targetLat = Double.NaN;
        }
        this.targetSpreadM = 0;
        invalidate();
    }

    /** Configures the map for multi-bearing triangulation. */
    public void setTriangulationData(List<Geodesic.Ray> rays, Geodesic.Intersection intersection,
                                     double meLat, double meLon) {
        this.rays = rays;
        this.p1Lat = Double.NaN;
        this.p2Lat = Double.NaN;
        this.meLat = meLat;
        this.meLon = meLon;
        if (intersection != null) {
            this.targetLat = intersection.latitude;
            this.targetLon = intersection.longitude;
            this.targetLabel = "TARGET";
            this.targetSpreadM = intersection.rmsMissM;
        } else {
            this.targetLat = Double.NaN;
            this.targetSpreadM = 0;
        }
        invalidate();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int w = MeasureSpec.getSize(widthMeasureSpec);
        setMeasuredDimension(w, Math.min(w, Ui.dp(getContext(), 260)));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth();
        int h = getHeight();
        canvas.drawRect(0, 0, w, h, borderPaint);

        // Determine reference coordinate
        double refLat = Double.NaN;
        double refLon = 0;
        if (!Double.isNaN(p1Lat)) {
            refLat = p1Lat;
            refLon = p1Lon;
        } else if (rays != null && !rays.isEmpty()) {
            refLat = rays.get(0).latitude;
            refLon = rays.get(0).longitude;
        } else if (!Double.isNaN(meLat)) {
            refLat = meLat;
            refLon = meLon;
        } else if (!Double.isNaN(targetLat)) {
            refLat = targetLat;
            refLon = targetLon;
        }
        if (Double.isNaN(refLat)) {
            textPaint.setTextAlign(Paint.Align.CENTER);
            canvas.drawText("No points to plot yet", w / 2f, h / 2f, textPaint);
            return;
        }

        double kx = 111_320.0 * Math.cos(Math.toRadians(refLat));
        double ky = 111_320.0;
        double minX = 0;
        double maxX = 0;
        double minY = 0;
        double maxY = 0;
        boolean any = false;

        if (!Double.isNaN(p1Lat)) {
            double x = Geodesic.wrapLongitude(p1Lon - refLon) * kx;
            double y = (p1Lat - refLat) * ky;
            minX = maxX = x;
            minY = maxY = y;
            any = true;
        }
        if (!Double.isNaN(p2Lat)) {
            double x = Geodesic.wrapLongitude(p2Lon - refLon) * kx;
            double y = (p2Lat - refLat) * ky;
            if (!any) { minX = maxX = x; minY = maxY = y; any = true; }
            else { minX = Math.min(minX, x); maxX = Math.max(maxX, x); minY = Math.min(minY, y); maxY = Math.max(maxY, y); }
        }
        if (rays != null) {
            for (Geodesic.Ray r : rays) {
                double x = Geodesic.wrapLongitude(r.longitude - refLon) * kx;
                double y = (r.latitude - refLat) * ky;
                if (!any) { minX = maxX = x; minY = maxY = y; any = true; }
                else { minX = Math.min(minX, x); maxX = Math.max(maxX, x); minY = Math.min(minY, y); maxY = Math.max(maxY, y); }
            }
        }
        if (!Double.isNaN(targetLat)) {
            double x = Geodesic.wrapLongitude(targetLon - refLon) * kx;
            double y = (targetLat - refLat) * ky;
            if (!any) { minX = maxX = x; minY = maxY = y; any = true; }
            else { minX = Math.min(minX, x); maxX = Math.max(maxX, x); minY = Math.min(minY, y); maxY = Math.max(maxY, y); }
        }
        if (!Double.isNaN(meLat)) {
            double x = Geodesic.wrapLongitude(meLon - refLon) * kx;
            double y = (meLat - refLat) * ky;
            if (!any) { minX = maxX = x; minY = maxY = y; any = true; }
            else { minX = Math.min(minX, x); maxX = Math.max(maxX, x); minY = Math.min(minY, y); maxY = Math.max(maxY, y); }
        }

        double span = Math.max(50.0, Math.max(maxX - minX, maxY - minY) * 1.25);
        float pad = 24 * dp;
        float scale = (float) (Math.min(w, h) - 2 * pad) / (float) span;
        double cx = (minX + maxX) / 2.0;
        double cy = (minY + maxY) / 2.0;
        float ox = w / 2f;
        float oy = h / 2f;

        // Subtle crosshair at center
        canvas.drawLine(0, oy, w, oy, gridPaint);
        canvas.drawLine(ox, 0, ox, h, gridPaint);

        // Connecting line between P1 and P2 or Target
        if (!Double.isNaN(p1Lat)) {
            float sx1 = ox + (float) ((Geodesic.wrapLongitude(p1Lon - refLon) * kx - cx) * scale);
            float sy1 = oy - (float) (((p1Lat - refLat) * ky - cy) * scale);
            if (!Double.isNaN(p2Lat)) {
                float sx2 = ox + (float) ((Geodesic.wrapLongitude(p2Lon - refLon) * kx - cx) * scale);
                float sy2 = oy - (float) (((p2Lat - refLat) * ky - cy) * scale);
                canvas.drawLine(sx1, sy1, sx2, sy2, linePaint);
                drawMarker(canvas, sx2, sy2, stationPaint, p2Label);
            } else if (!Double.isNaN(targetLat)) {
                float sxt = ox + (float) ((Geodesic.wrapLongitude(targetLon - refLon) * kx - cx) * scale);
                float syt = oy - (float) (((targetLat - refLat) * ky - cy) * scale);
                canvas.drawLine(sx1, sy1, sxt, syt, rayPaint);
            }
            drawMarker(canvas, sx1, sy1, mePaint, p1Label);
        }

        // Bearing rays
        if (rays != null) {
            float rayLenPx = Math.max(w, h) * 1.5f;
            for (Geodesic.Ray r : rays) {
                float sx = ox + (float) ((Geodesic.wrapLongitude(r.longitude - refLon) * kx - cx) * scale);
                float sy = oy - (float) (((r.latitude - refLat) * ky - cy) * scale);
                double rad = Math.toRadians(r.azimuthDeg);
                float ex = sx + (float) Math.sin(rad) * rayLenPx;
                float ey = sy - (float) Math.cos(rad) * rayLenPx;
                canvas.drawLine(sx, sy, ex, ey, rayPaint);
                drawMarker(canvas, sx, sy, stationPaint, r.label);
            }
        }

        // Live observer when in triangulation mode
        if (rays != null && !Double.isNaN(meLat)) {
            float sx = ox + (float) ((Geodesic.wrapLongitude(meLon - refLon) * kx - cx) * scale);
            float sy = oy - (float) (((meLat - refLat) * ky - cy) * scale);
            mePaint.setAlpha(60);
            canvas.drawCircle(sx, sy, 10 * dp, mePaint);
            mePaint.setAlpha(255);
            canvas.drawCircle(sx, sy, 4.5f * dp, mePaint);
        }

        // Target / intersection marker
        if (!Double.isNaN(targetLat)) {
            float sxt = ox + (float) ((Geodesic.wrapLongitude(targetLon - refLon) * kx - cx) * scale);
            float syt = oy - (float) (((targetLat - refLat) * ky - cy) * scale);
            if (targetSpreadM > 0.5) {
                float rPx = (float) (targetSpreadM * scale);
                canvas.drawCircle(sxt, syt, Math.max(8 * dp, rPx), errorCirclePaint);
            }
            canvas.drawCircle(sxt, syt, 6 * dp, targetPaint);
            textPaint.setTextAlign(Paint.Align.LEFT);
            if (targetLabel != null) {
                canvas.drawText(targetLabel, sxt + 8 * dp, syt + 4 * dp, textPaint);
            }
        }

        textPaint.setTextAlign(Paint.Align.LEFT);
        canvas.drawText("N ↑", 6 * dp, 14 * dp, textPaint);
        canvas.drawText(String.format(Locale.ROOT, "%s across", Format.distance(span)), 6 * dp, h - 6 * dp, textPaint);
    }

    private void drawMarker(Canvas canvas, float sx, float sy, Paint paint, String label) {
        canvas.drawCircle(sx, sy, 5.5f * dp, paint);
        if (label != null && !label.isEmpty()) {
            textPaint.setTextAlign(Paint.Align.LEFT);
            canvas.drawText(label, sx + 8 * dp, sy + 4 * dp, textPaint);
        }
    }
}

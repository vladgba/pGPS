package x.vladgba.gps.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;

import x.vladgba.gps.R;
import x.vladgba.gps.nav.Trail;

import java.util.List;
import java.util.Locale;

/**
 * Local map of the breadcrumb trail (north up, metres), fitted to the trail and the current
 * position: start (green), trail, current position (blue) and the breadcrumb being aimed at.
 */
public final class TrailMapView extends View {

    private final Paint trailPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint startPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint targetPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint gridPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final float dp;

    private List<Trail.Point> points;
    private double meLat = Double.NaN;
    private double meLon;
    private double targetLat = Double.NaN;
    private double targetLon;

    public TrailMapView(Context context) {
        super(context);
        dp = Ui.dp(context, 1);
        trailPaint.setStyle(Paint.Style.STROKE);
        trailPaint.setStrokeWidth(3 * dp);
        trailPaint.setStrokeJoin(Paint.Join.ROUND);
        trailPaint.setStrokeCap(Paint.Cap.ROUND);
        trailPaint.setColor(0xFFFFA726);
        startPaint.setColor(0xFF66BB6A);
        mePaint.setColor(0xFF4FC3F7);
        targetPaint.setColor(0xFFFF4081);
        textPaint.setTextSize(Ui.sp(context, 11));
        textPaint.setColor(context.getColor(R.color.text_secondary));
        gridPaint.setStyle(Paint.Style.STROKE);
        gridPaint.setStrokeWidth(dp);
        gridPaint.setColor(context.getColor(R.color.divider));
    }

    public void setData(List<Trail.Point> points, double meLat, double meLon, double targetLat, double targetLon) {
        this.points = points;
        this.meLat = meLat;
        this.meLon = meLon;
        this.targetLat = targetLat;
        this.targetLon = targetLon;
        invalidate();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int w = MeasureSpec.getSize(widthMeasureSpec);
        setMeasuredDimension(w, Math.min(w, Ui.dp(getContext(), 320)));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        canvas.drawRect(0, 0, getWidth(), getHeight(), gridPaint);
        boolean haveMe = !Double.isNaN(meLat);
        if ((points == null || points.isEmpty()) && !haveMe) return;

        // Reference and bounds in local metres.
        double refLat = points != null && !points.isEmpty() ? points.get(0).latitude : meLat;
        double refLon = points != null && !points.isEmpty() ? points.get(0).longitude : meLon;
        double kx = 111_320 * Math.cos(Math.toRadians(refLat));
        double ky = 111_320;
        double minX = 0;
        double maxX = 0;
        double minY = 0;
        double maxY = 0;
        if (points != null) {
            for (Trail.Point p : points) {
                double x = (p.longitude - refLon) * kx;
                double y = (p.latitude - refLat) * ky;
                minX = Math.min(minX, x);
                maxX = Math.max(maxX, x);
                minY = Math.min(minY, y);
                maxY = Math.max(maxY, y);
            }
        }
        if (haveMe) {
            double x = (meLon - refLon) * kx;
            double y = (meLat - refLat) * ky;
            minX = Math.min(minX, x);
            maxX = Math.max(maxX, x);
            minY = Math.min(minY, y);
            maxY = Math.max(maxY, y);
        }
        double span = Math.max(40, Math.max(maxX - minX, maxY - minY));
        float pad = 18 * dp;
        float scale = (float) (Math.min(getWidth(), getHeight()) - 2 * pad) / (float) span;
        double cx = (minX + maxX) / 2;
        double cy = (minY + maxY) / 2;
        float ox = getWidth() / 2f;
        float oy = getHeight() / 2f;

        if (points != null && points.size() > 1) {
            path.reset();
            for (int i = 0; i < points.size(); i++) {
                Trail.Point p = points.get(i);
                float sx = ox + (float) (((p.longitude - refLon) * kx - cx) * scale);
                float sy = oy - (float) (((p.latitude - refLat) * ky - cy) * scale);
                if (i == 0) path.moveTo(sx, sy);
                else path.lineTo(sx, sy);
            }
            canvas.drawPath(path, trailPaint);
        }
        if (points != null && !points.isEmpty()) {
            float sx = ox + (float) ((0 - cx) * scale);
            float sy = oy - (float) ((0 - cy) * scale);
            canvas.drawCircle(sx, sy, 7 * dp, startPaint);
            canvas.drawText("START", sx + 9 * dp, sy + 4 * dp, textPaint);
        }
        if (!Double.isNaN(targetLat)) {
            float sx = ox + (float) (((targetLon - refLon) * kx - cx) * scale);
            float sy = oy - (float) (((targetLat - refLat) * ky - cy) * scale);
            canvas.drawCircle(sx, sy, 5 * dp, targetPaint);
        }
        if (haveMe) {
            float sx = ox + (float) (((meLon - refLon) * kx - cx) * scale);
            float sy = oy - (float) (((meLat - refLat) * ky - cy) * scale);
            mePaint.setAlpha(60);
            canvas.drawCircle(sx, sy, 12 * dp, mePaint);
            mePaint.setAlpha(255);
            canvas.drawCircle(sx, sy, 6 * dp, mePaint);
        }
        textPaint.setTextAlign(Paint.Align.LEFT);
        canvas.drawText("N ↑", 6 * dp, 14 * dp, textPaint);
        canvas.drawText(String.format(Locale.ROOT, "%s across", Format.distance(span)), 6 * dp, getHeight() - 6 * dp, textPaint);
    }
}

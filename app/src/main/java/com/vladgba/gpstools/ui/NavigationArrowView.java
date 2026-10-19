package com.vladgba.gpstools.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.hardware.SensorManager;
import android.util.AttributeSet;
import android.view.Display;
import android.view.Surface;
import android.view.View;

import com.vladgba.gpstools.R;

/**
 * Compass rose plus an arrow pointing at the target. Heading comes from the orientation
 * tracker when the compass is trusted (or north was set by hand), otherwise from the GPS course
 * while moving. Sensors run only while the view is on screen.
 */
public final class NavigationArrowView extends View implements OrientationTracker.Callback {

    public enum HeadingSource { COMPASS, COURSE, DISTURBED, NONE }

    private static final float MIN_COURSE_SPEED = 1.0f; // m/s

    private final OrientationTracker tracker;
    private final float[] remapped = new float[9];
    private final Paint ringPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tickPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint northPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint arrowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final float dp;

    private double targetBearing = Double.NaN;
    private float course = Float.NaN;
    private float speed;
    private float heading = Float.NaN;
    private HeadingSource source = HeadingSource.NONE;
    private boolean enabled;

    public NavigationArrowView(Context context) {
        this(context, null);
    }

    public NavigationArrowView(Context context, AttributeSet attrs) {
        super(context, attrs);
        dp = Ui.dp(context, 1);
        tracker = new OrientationTracker(context, this);
        ringPaint.setStyle(Paint.Style.STROKE);
        ringPaint.setStrokeWidth(2 * dp);
        ringPaint.setColor(context.getColor(R.color.divider));
        tickPaint.setColor(context.getColor(R.color.text_secondary));
        tickPaint.setTextSize(Ui.sp(context, 12));
        tickPaint.setTextAlign(Paint.Align.CENTER);
        northPaint.setColor(0xFFFF8A65);
        northPaint.setTextSize(Ui.sp(context, 15));
        northPaint.setTextAlign(Paint.Align.CENTER);
        northPaint.setFakeBoldText(true);
        arrowPaint.setColor(0xFFFF4081);
    }

    /** @param bearing true bearing to the target, NaN when there is none */
    public void setNavigation(double bearing, float gpsCourse, float gpsSpeed, double lat, double lon, double alt, long time) {
        targetBearing = bearing;
        course = gpsCourse;
        speed = gpsSpeed;
        if (!Double.isNaN(lat)) tracker.setObserver(lat, lon, alt, time);
        updateHeading();
        invalidate();
    }

    public void setActive(boolean active) {
        enabled = active;
        updateSensors();
    }

    public HeadingSource getHeadingSource() {
        return source;
    }

    /** Current heading in degrees (true), NaN when unknown. */
    public float getHeading() {
        return heading;
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        updateSensors();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        updateSensors();
    }

    @Override
    protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        updateSensors();
    }

    private void updateSensors() {
        boolean want = enabled && isAttachedToWindow() && getWindowVisibility() == VISIBLE;
        if (want && !tracker.isRunning()) tracker.start();
        else if (!want && tracker.isRunning()) tracker.stop();
    }

    @Override
    public void onOrientationChanged() {
        updateHeading();
        invalidate();
    }

    private void updateHeading() {
        boolean compassOk = tracker.isRunning() && tracker.hasFrame()
                && (tracker.isCompassTrusted() || tracker.isManualNorth()) && tracker.hasHeadingReference();
        if (compassOk) {
            heading = screenHeading();
            source = HeadingSource.COMPASS;
        } else if (!Float.isNaN(course) && speed >= MIN_COURSE_SPEED) {
            heading = course;
            source = HeadingSource.COURSE;
        } else if (tracker.isRunning() && tracker.hasFrame()) {
            heading = screenHeading();
            source = HeadingSource.DISTURBED;
        } else {
            heading = Float.NaN;
            source = HeadingSource.NONE;
        }
    }

    /** Azimuth of the top of the screen (phone flat) or of the back camera (phone upright). */
    private float screenHeading() {
        Display display = getDisplay();
        float[] r = tracker.getRotation();
        int rotation = display != null ? display.getRotation() : Surface.ROTATION_0;
        switch (rotation) {
            case Surface.ROTATION_90:
                SensorManager.remapCoordinateSystem(r, SensorManager.AXIS_Y, SensorManager.AXIS_MINUS_X, remapped);
                break;
            case Surface.ROTATION_180:
                SensorManager.remapCoordinateSystem(r, SensorManager.AXIS_MINUS_X, SensorManager.AXIS_MINUS_Y, remapped);
                break;
            case Surface.ROTATION_270:
                SensorManager.remapCoordinateSystem(r, SensorManager.AXIS_MINUS_Y, SensorManager.AXIS_X, remapped);
                break;
            default:
                System.arraycopy(r, 0, remapped, 0, 9);
                break;
        }
        double x;
        double y;
        if (Math.abs(remapped[8]) > 0.7) {
            x = remapped[1];
            y = remapped[4];
        } else {
            x = -remapped[2];
            y = -remapped[5];
        }
        double deg = Math.toDegrees(Math.atan2(x, y));
        return (float) ((deg % 360 + 360) % 360);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float cx = getWidth() / 2f;
        float cy = getHeight() / 2f;
        float radius = Math.min(cx, cy) - 18 * dp;
        canvas.drawCircle(cx, cy, radius, ringPaint);
        float h = Float.isNaN(heading) ? 0 : heading;

        // Compass rose rotates opposite to the heading so N points to true north.
        String[] names = {"N", "E", "S", "W"};
        float textCenter = -(tickPaint.descent() + tickPaint.ascent()) / 2f;
        for (int i = 0; i < 4; i++) {
            double a = Math.toRadians(i * 90 - h);
            float x = cx + (float) (Math.sin(a) * (radius + 10 * dp));
            float y = cy - (float) (Math.cos(a) * (radius + 10 * dp));
            canvas.drawText(names[i], x, y + textCenter, i == 0 ? northPaint : tickPaint);
        }
        if (Double.isNaN(targetBearing) || Float.isNaN(heading)) return;

        canvas.save();
        canvas.rotate((float) (targetBearing - h), cx, cy);
        float len = radius * 0.8f;
        path.reset();
        path.moveTo(cx, cy - len);
        path.lineTo(cx + len * 0.32f, cy + len * 0.55f);
        path.lineTo(cx, cy + len * 0.32f);
        path.lineTo(cx - len * 0.32f, cy + len * 0.55f);
        path.close();
        arrowPaint.setAlpha(source == HeadingSource.DISTURBED ? 110 : 255);
        canvas.drawPath(path, arrowPaint);
        canvas.restore();
    }
}

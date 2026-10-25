package com.vladgba.gpstools.ui;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.hardware.SensorManager;
import android.location.GnssStatus;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.Display;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.Surface;
import android.view.View;

import com.vladgba.gpstools.R;
import com.vladgba.gpstools.gnss.Constellations;
import com.vladgba.gpstools.gnss.SatelliteInfo;
import com.vladgba.gpstools.gnss.SunPosition;
import com.vladgba.gpstools.nav.Waypoint;
import com.vladgba.gpstools.orbit.OrbitCatalog;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * First-person sky view in the spirit of Stellarium: horizon, ground, alt/az grid, compass
 * points, the Sun, tracked satellites with motion trails, predicted satellites from orbit data
 * (including below the horizon), the selected satellite's orbit and the navigation target.
 *
 * <p>Gnomonic (rectilinear) projection: what a camera would see. The horizon is then always a
 * straight line, so the ground is an exact half-plane of the screen. World frame is ENU:
 * x = east, y = true north, z = up. The camera follows the phone ({@link OrientationTracker})
 * or is dragged and pinched by hand.
 *
 * <p>AR mode: the view is drawn transparent over a live camera preview ({@link ArCameraView}),
 * always follows the phone and uses the camera's focal length instead of a free FOV. Pinch then
 * fine-tunes the scale (lens data is not always exact) and a sideways drag aligns north.
 */
public final class Sky3DView extends View implements OrientationTracker.Callback {

    public interface Listener {
        /** The view left compass mode on its own (the user dragged it). */
        void onFollowSensorChanged(boolean follow);
    }

    private static final float MIN_FOV = 20f;
    private static final float MAX_FOV = 120f;
    private static final float MIN_PITCH = -60f;
    private static final float MAX_PITCH = 90f;
    private static final double MIN_DEPTH = 0.02;
    private static final long TRAIL_SAMPLE_MS = 15_000;
    private static final int TRAIL_MAX_POINTS = 80; // 20 minutes
    private static final float SENSOR_SMOOTHING = 0.35f;
    private static final long TRACK_HALF_SPAN_MS = 3 * 3600_000L;
    private static final long TRACK_STEP_MS = 5 * 60_000L;
    private static final long TRACK_REFRESH_MS = 60_000L;
    /** Less lag in AR, where the overlay must stay on the camera image. */
    private static final float AR_SENSOR_SMOOTHING = 0.6f;
    private static final float MIN_AR_SCALE = 0.6f;
    private static final float MAX_AR_SCALE = 1.6f;
    private static final String PREFS = "sky3d";
    private static final String PREF_AR_SCALE = "ar_scale";

    // ---- Camera ---------------------------------------------------------------------------
    private float yawDeg = 180f;   // looking south by default
    private float pitchDeg = 25f;
    private float fovDeg = 90f;
    private final double[] fwd = new double[3];
    private final double[] right = new double[3];
    private final double[] up = new double[3];
    private double focal;

    // ---- AR -------------------------------------------------------------------------------
    private boolean arMode;
    private boolean followBeforeAr;
    private double arFocalPx = Double.NaN;
    private float arScale = 1f;
    private final Paint arScrimPaint = new Paint();
    private final Paint arGroundPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    // ---- Orientation ----------------------------------------------------------------------
    private final OrientationTracker tracker;
    private final float[] remapped = new float[9];
    private boolean followSensor;
    private boolean haveSensorFrame;
    private Listener listener;

    // ---- Data -----------------------------------------------------------------------------
    private List<SatelliteInfo> all = Collections.emptyList();
    private List<SatelliteInfo> markers = Collections.emptyList();
    private final Set<Long> trackedKeys = new HashSet<>();
    private final Map<Long, ArrayDeque<float[]>> trails = new HashMap<>();
    private final Map<Long, Long> trailSampledAt = new HashMap<>();
    private List<OrbitCatalog.Position> predictions = Collections.emptyList();
    private OrbitCatalog catalog;
    private boolean showPredictions = true;
    private List<float[]> selectedTrack;
    private long selectedTrackKey;
    private long selectedTrackAt;
    private long selectedKey = Long.MIN_VALUE;
    private boolean haveObserver;
    private double observerLat;
    private double observerLon;
    private double observerAlt;
    private Waypoint target;
    private boolean interacted;

    // ---- Drawing --------------------------------------------------------------------------
    private final Paint skyPaint = new Paint();
    private final Paint groundPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glowPaint = new Paint();
    private final Paint horizonPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint gridPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint gridLabelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint cardinalPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint markerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint smallLabelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint trailPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint orbitPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint sunPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint targetPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint hudPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint cardPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint cardTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final float[] point = new float[2];
    private final RectF rect = new RectF();
    private final float dp;

    private final GestureDetector gestures;
    private final ScaleGestureDetector scaleGestures;

    public Sky3DView(Context context) {
        this(context, null);
    }

    public Sky3DView(Context context, AttributeSet attrs) {
        super(context, attrs);
        dp = Ui.dp(context, 1);
        tracker = new OrientationTracker(context, this);

        skyPaint.setColor(0xFF0A1222);
        groundPaint.setColor(0xF01B1912);
        horizonPaint.setStyle(Paint.Style.STROKE);
        horizonPaint.setStrokeWidth(2 * dp);
        horizonPaint.setColor(0xFF5E7F9E);
        gridPaint.setStyle(Paint.Style.STROKE);
        gridPaint.setStrokeWidth(dp);
        gridPaint.setColor(0x334FC3F7);
        gridLabelPaint.setTextSize(Ui.sp(context, 10));
        gridLabelPaint.setColor(0x994FC3F7);
        cardinalPaint.setColor(0xFFFF8A65);
        cardinalPaint.setTextAlign(Paint.Align.CENTER);
        cardinalPaint.setFakeBoldText(true);
        labelPaint.setTextSize(Ui.sp(context, 11));
        labelPaint.setFakeBoldText(true);
        smallLabelPaint.setTextSize(Ui.sp(context, 9));
        trailPaint.setStyle(Paint.Style.STROKE);
        trailPaint.setStrokeWidth(1.5f * dp);
        trailPaint.setStrokeCap(Paint.Cap.ROUND);
        orbitPaint.setStyle(Paint.Style.STROKE);
        orbitPaint.setStrokeWidth(1.5f * dp);
        orbitPaint.setPathEffect(new DashPathEffect(new float[]{6 * dp, 5 * dp}, 0));
        sunPaint.setColor(0xFFFFD54F);
        targetPaint.setColor(0xFFFF4081);
        targetPaint.setTextSize(Ui.sp(context, 12));
        targetPaint.setFakeBoldText(true);
        hudPaint.setTextSize(Ui.sp(context, 11));
        hudPaint.setColor(context.getColor(R.color.text_secondary));
        cardPaint.setColor(0xE6171B22);
        cardTextPaint.setTextSize(Ui.sp(context, 13));
        cardTextPaint.setColor(context.getColor(R.color.text_primary));
        arScrimPaint.setColor(0x33000814);
        arGroundPaint.setColor(0x55201A0E);
        try {
            float savedScale = prefs().getFloat(PREF_AR_SCALE, 1f);
            if (Float.isFinite(savedScale)) arScale = clamp(savedScale, MIN_AR_SCALE, MAX_AR_SCALE);
        } catch (RuntimeException ignored) {
            // Use the default calibration if preferences are unavailable or malformed.
        }

        gestures = new GestureDetector(context, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDown(MotionEvent e) {
                return true;
            }

            @Override
            public boolean onScroll(MotionEvent e1, MotionEvent e2, float dx, float dy) {
                float degPerPx = fovDeg / Math.max(1, Math.min(getWidth(), getHeight()));
                interacted = true;
                if (arMode) {
                    // AR always follows the phone; a drag only aligns north with the image.
                    if (tracker.isFused() && haveSensorFrame) {
                        tracker.adjustNorth(-Math.toRadians(dx * degPerPx));
                        invalidate();
                    }
                    return true;
                }
                if (followSensor && tracker.isFused() && haveSensorFrame) {
                    // In compass mode a sideways drag sets north by hand (bad compass).
                    tracker.adjustNorth(-Math.toRadians(dx * degPerPx));
                    invalidate();
                    return true;
                }
                if (followSensor) setFollowSensor(false, true);
                yawDeg = normalize(yawDeg + dx * degPerPx);
                pitchDeg = clamp(pitchDeg - dy * degPerPx, MIN_PITCH, MAX_PITCH);
                invalidate();
                return true;
            }

            @Override
            public boolean onSingleTapUp(MotionEvent e) {
                selectAt(e.getX(), e.getY());
                return true;
            }

            @Override
            public boolean onDoubleTap(MotionEvent e) {
                if (arMode) setArScale(1f);
                else fovDeg = 90f;
                invalidate();
                return true;
            }
        });
        scaleGestures = new ScaleGestureDetector(context, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScale(ScaleGestureDetector detector) {
                interacted = true;
                if (arMode) {
                    // Spreading the fingers enlarges the overlay: a longer focal length.
                    setArScale(clamp(arScale * detector.getScaleFactor(), MIN_AR_SCALE, MAX_AR_SCALE));
                    return true;
                }
                fovDeg = clamp(fovDeg / detector.getScaleFactor(), MIN_FOV, MAX_FOV);
                interacted = true;
                invalidate();
                return true;
            }
        });
    }

    // ---- Public API ---------------------------------------------------------------------------

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    public boolean hasOrientationSensor() {
        return tracker.isAvailable();
    }

    public boolean isFollowSensor() {
        return followSensor;
    }

    public void setFollowSensor(boolean follow) {
        setFollowSensor(follow, false);
    }

    private void setFollowSensor(boolean follow, boolean fromUser) {
        if (follow && !tracker.isAvailable()) follow = false;
        if (arMode && !follow) return; // AR is meaningless without the phone's attitude
        if (followSensor == follow) return;
        followSensor = follow;
        haveSensorFrame = false;
        if (!follow) {
            // Keep looking where the phone was pointing.
            yawDeg = lookAzimuth();
            pitchDeg = clamp(lookAltitude(), MIN_PITCH, MAX_PITCH);
        }
        updateSensorRegistration();
        if (fromUser && listener != null) listener.onFollowSensorChanged(follow);
        invalidate();
    }

    public boolean isArMode() {
        return arMode;
    }

    /**
     * AR overlay on/off. Turning it on enables compass mode; turning it off restores the mode
     * that was active before.
     */
    public void setArMode(boolean ar) {
        if (arMode == ar || (ar && !tracker.isAvailable())) return;
        if (ar) {
            followBeforeAr = followSensor;
            setFollowSensor(true);
            arMode = true;
        } else {
            arMode = false;
            setFollowSensor(followBeforeAr);
        }
        invalidate();
    }

    /** Focal length of the camera preview behind the view, in view pixels. */
    public void setArFocalPx(double focalPx) {
        arFocalPx = Double.isFinite(focalPx) && focalPx > 0 ? focalPx : Double.NaN;
        invalidate();
    }

    private void setArScale(float scale) {
        arScale = scale;
        try {
            prefs().edit().putFloat(PREF_AR_SCALE, scale).apply();
        } catch (RuntimeException ignored) {
            // Preferences unavailable: keep the value for this session only.
        }
        invalidate();
    }

    private SharedPreferences prefs() {
        return getContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** Observer position for the Sun, orbits, declination and expected magnetic field. */
    public void setObserver(double lat, double lon, double altitudeM, long timeMs) {
        if (Double.isNaN(lat)) return;
        haveObserver = true;
        observerLat = lat;
        observerLon = lon;
        observerAlt = altitudeM;
        tracker.setObserver(lat, lon, altitudeM, timeMs);
    }

    public void setSatellites(List<SatelliteInfo> satellites) {
        all = satellites;
        markers = SatelliteMarkers.merge(satellites);
        trackedKeys.clear();
        long now = SystemClock.elapsedRealtime();
        for (SatelliteInfo s : markers) {
            trackedKeys.add(s.key());
            Long last = trailSampledAt.get(s.key());
            if (last != null && now - last < TRAIL_SAMPLE_MS) continue;
            ArrayDeque<float[]> trail = trails.get(s.key());
            if (trail == null) {
                trail = new ArrayDeque<>();
                trails.put(s.key(), trail);
            }
            trail.addLast(new float[]{s.elevation, s.azimuth});
            if (trail.size() > TRAIL_MAX_POINTS) trail.removeFirst();
            trailSampledAt.put(s.key(), now);
        }
        invalidate();
    }

    /** Predicted positions of every catalog satellite (tracked ones are drawn from live data). */
    public void setPredictions(List<OrbitCatalog.Position> positions, OrbitCatalog catalog) {
        this.predictions = positions;
        this.catalog = catalog;
        invalidate();
    }

    public void setShowPredictions(boolean show) {
        showPredictions = show;
        invalidate();
    }

    public boolean isShowPredictions() {
        return showPredictions;
    }

    /** Navigation target drawn on the horizon, or null. */
    public void setTarget(Waypoint target) {
        this.target = target;
        invalidate();
    }

    // ---- Lifecycle: the sensors only run while the view is on screen --------------------------

    @Override
    protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        updateSensorRegistration();
    }

    @Override
    protected void onVisibilityChanged(View changedView, int visibility) {
        super.onVisibilityChanged(changedView, visibility);
        updateSensorRegistration();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        updateSensorRegistration();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        updateSensorRegistration();
    }

    private void updateSensorRegistration() {
        boolean want = followSensor && isAttachedToWindow() && getWindowVisibility() == VISIBLE && isShown();
        if (want && !tracker.isRunning()) tracker.start();
        else if (!want && tracker.isRunning()) tracker.stop();
    }

    @Override
    public void onOrientationChanged() {
        if (!followSensor) return;
        remapForDisplay(tracker.getRotation());
        // Columns are the device axes in the world frame (true north).
        double[] r = {remapped[0], remapped[3], remapped[6]};
        double[] f = {-remapped[2], -remapped[5], -remapped[8]}; // camera looks out of the back
        if (!haveSensorFrame) {
            System.arraycopy(f, 0, fwd, 0, 3);
            System.arraycopy(r, 0, right, 0, 3);
            haveSensorFrame = true;
        } else {
            float k = arMode ? AR_SENSOR_SMOOTHING : SENSOR_SMOOTHING;
            for (int i = 0; i < 3; i++) {
                fwd[i] += (f[i] - fwd[i]) * k;
                right[i] += (r[i] - right[i]) * k;
            }
        }
        orthonormalize();
        invalidate();
    }

    private void remapForDisplay(float[] rotation) {
        Display display = getDisplay();
        int rotationIndex = display != null ? display.getRotation() : Surface.ROTATION_0;
        switch (rotationIndex) {
            case Surface.ROTATION_90:
                SensorManager.remapCoordinateSystem(rotation, SensorManager.AXIS_Y, SensorManager.AXIS_MINUS_X, remapped);
                break;
            case Surface.ROTATION_180:
                SensorManager.remapCoordinateSystem(rotation, SensorManager.AXIS_MINUS_X, SensorManager.AXIS_MINUS_Y, remapped);
                break;
            case Surface.ROTATION_270:
                SensorManager.remapCoordinateSystem(rotation, SensorManager.AXIS_MINUS_Y, SensorManager.AXIS_X, remapped);
                break;
            default:
                System.arraycopy(rotation, 0, remapped, 0, 9);
                break;
        }
    }

    // ---- Camera math --------------------------------------------------------------------------

    private void updateCamera() {
        if (!followSensor || !haveSensorFrame) {
            double yaw = Math.toRadians(yawDeg);
            double pitch = Math.toRadians(pitchDeg);
            fwd[0] = Math.sin(yaw) * Math.cos(pitch);
            fwd[1] = Math.cos(yaw) * Math.cos(pitch);
            fwd[2] = Math.sin(pitch);
            right[0] = Math.cos(yaw);
            right[1] = -Math.sin(yaw);
            right[2] = 0;
        }
        orthonormalize();
        if (arMode && !Double.isNaN(arFocalPx) && arFocalPx > 0) {
            focal = arFocalPx * arScale;
        } else {
            focal = (Math.min(getWidth(), getHeight()) / 2.0) / Math.tan(Math.toRadians(fovDeg) / 2.0);
        }
    }

    /** Field of view across the shorter screen side, from the focal length actually used. */
    private float effectiveFovDeg() {
        if (focal <= 0) return fovDeg;
        return (float) Math.toDegrees(2 * Math.atan(Math.min(getWidth(), getHeight()) / 2.0 / focal));
    }

    private void orthonormalize() {
        normalize(fwd);
        double dot = dot(right, fwd);
        for (int i = 0; i < 3; i++) right[i] -= dot * fwd[i];
        normalize(right);
        // up = right × fwd
        up[0] = right[1] * fwd[2] - right[2] * fwd[1];
        up[1] = right[2] * fwd[0] - right[0] * fwd[2];
        up[2] = right[0] * fwd[1] - right[1] * fwd[0];
    }

    /** Projects an alt/az direction; false when it is behind or beside the camera. */
    private boolean project(double altDeg, double azDeg, float[] out) {
        double alt = Math.toRadians(altDeg);
        double az = Math.toRadians(azDeg);
        double dx = Math.sin(az) * Math.cos(alt);
        double dy = Math.cos(az) * Math.cos(alt);
        double dz = Math.sin(alt);
        double depth = dx * fwd[0] + dy * fwd[1] + dz * fwd[2];
        if (depth < MIN_DEPTH) return false;
        double cx = dx * right[0] + dy * right[1] + dz * right[2];
        double cy = dx * up[0] + dy * up[1] + dz * up[2];
        out[0] = (float) (getWidth() / 2.0 + focal * cx / depth);
        out[1] = (float) (getHeight() / 2.0 - focal * cy / depth);
        return true;
    }

    /** World "up" component of the ray through a screen point: > 0 sky, < 0 ground (linear). */
    private double skySide(double sx, double sy) {
        double x = (sx - getWidth() / 2.0) / focal;
        double y = -(sy - getHeight() / 2.0) / focal;
        return fwd[2] + x * right[2] + y * up[2];
    }

    // ---- Drawing ------------------------------------------------------------------------------

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth();
        int h = getHeight();
        if (w == 0 || h == 0) return;
        updateCamera();

        if (arMode) {
            // The camera image is the sky: only a light scrim keeps the labels readable.
            canvas.drawRect(0, 0, w, h, arScrimPaint);
        } else {
            canvas.drawRect(0, 0, w, h, skyPaint);
            drawHorizonGlow(canvas);
        }
        drawGrid(canvas);
        drawSun(canvas);
        drawPredictions(canvas, true);
        drawSelectedOrbit(canvas);
        drawTrails(canvas);
        drawGround(canvas);
        drawPredictions(canvas, false);
        drawCardinals(canvas);
        drawTarget(canvas);
        drawSatellites(canvas);
        drawHud(canvas);
        drawSelection(canvas);
    }

    private void drawHorizonGlow(Canvas canvas) {
        double gx = right[2] / focal;
        double gy = -up[2] / focal;
        double g2 = gx * gx + gy * gy;
        if (g2 < 1e-12) return;
        double cx = getWidth() / 2.0;
        double cy = getHeight() / 2.0;
        double g0 = fwd[2];
        // Closest horizon point to the screen centre, then 18° of glow towards the sky.
        float px = (float) (cx - g0 * gx / g2);
        float py = (float) (cy - g0 * gy / g2);
        double len = focal * Math.tan(Math.toRadians(18));
        double norm = Math.sqrt(g2);
        float qx = (float) (px + gx / norm * len);
        float qy = (float) (py + gy / norm * len);
        glowPaint.setShader(new LinearGradient(px, py, qx, qy, 0xFF1F3A5C, 0x000A1222, Shader.TileMode.CLAMP));
        canvas.drawRect(0, 0, getWidth(), getHeight(), glowPaint);
    }

    /** Ground = screen rectangle clipped to the half-plane skySide < 0 (Sutherland–Hodgman). */
    private void drawGround(Canvas canvas) {
        float[][] corners = {{0, 0}, {getWidth(), 0}, {getWidth(), getHeight()}, {0, getHeight()}};
        List<float[]> polygon = new ArrayList<>();
        List<float[]> horizon = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            float[] a = corners[i];
            float[] b = corners[(i + 1) % 4];
            double va = skySide(a[0], a[1]);
            double vb = skySide(b[0], b[1]);
            if (va <= 0) polygon.add(a);
            if ((va <= 0) != (vb <= 0)) {
                double t = va / (va - vb);
                float[] p = {(float) (a[0] + (b[0] - a[0]) * t), (float) (a[1] + (b[1] - a[1]) * t)};
                polygon.add(p);
                horizon.add(p);
            }
        }
        if (polygon.size() >= 3) {
            path.reset();
            path.moveTo(polygon.get(0)[0], polygon.get(0)[1]);
            for (int i = 1; i < polygon.size(); i++) path.lineTo(polygon.get(i)[0], polygon.get(i)[1]);
            path.close();
            canvas.drawPath(path, arMode ? arGroundPaint : groundPaint);
        }
        if (horizon.size() == 2) {
            canvas.drawLine(horizon.get(0)[0], horizon.get(0)[1], horizon.get(1)[0], horizon.get(1)[1], horizonPaint);
        }
    }

    private void drawGrid(Canvas canvas) {
        for (int alt = 15; alt < 90; alt += 15) {
            drawPolyline(canvas, alt, 0, 0, 2, 181, gridPaint);
            float labelAz = Math.round(lookAzimuth() / 30f) * 30f;
            if (project(alt, labelAz, point)) {
                canvas.drawText(alt + "°", point[0] + 3 * dp, point[1] - 3 * dp, gridLabelPaint);
            }
        }
        for (int az = 0; az < 360; az += 30) {
            drawPolyline(canvas, 0, az, 2, 0, 45, gridPaint);
        }
    }

    /** Polyline from (alt0, az0) stepping (dAlt, dAz) n times, broken where not projectable. */
    private void drawPolyline(Canvas canvas, double alt0, double az0, double dAlt, double dAz, int n, Paint paint) {
        path.reset();
        boolean pen = false;
        for (int i = 0; i < n; i++) {
            if (project(alt0 + i * dAlt, az0 + i * dAz, point)) {
                if (pen) path.lineTo(point[0], point[1]);
                else path.moveTo(point[0], point[1]);
                pen = true;
            } else {
                pen = false;
            }
        }
        canvas.drawPath(path, paint);
    }

    private void drawCardinals(Canvas canvas) {
        String[] names = {"N", "NE", "E", "SE", "S", "SW", "W", "NW"};
        for (int i = 0; i < names.length; i++) {
            if (project(0, i * 45, point)) {
                cardinalPaint.setTextSize(Ui.sp(getContext(), i % 2 == 0 ? 18 : 13));
                canvas.drawText(names[i], point[0], point[1] - 6 * dp, cardinalPaint);
            }
        }
    }

    private void drawSun(Canvas canvas) {
        if (!haveObserver) return;
        double[] sun = SunPosition.altAz(System.currentTimeMillis(), observerLat, observerLon);
        if (!project(sun[0], sun[1], point)) return;
        // The Sun is 0.53° wide; never draw it smaller than a readable dot.
        float radius = (float) Math.max(7 * dp, focal * Math.tan(Math.toRadians(0.53 / 2)));
        sunPaint.setAlpha(60);
        canvas.drawCircle(point[0], point[1], radius * 2.2f, sunPaint);
        sunPaint.setAlpha(255);
        canvas.drawCircle(point[0], point[1], radius, sunPaint);
        labelPaint.setColor(0xFFFFD54F);
        canvas.drawText("Sun", point[0] + radius + 4 * dp, point[1] + 4 * dp, labelPaint);
    }

    /**
     * Predicted satellites the receiver does not track: small hollow diamonds. Above-horizon ones
     * are drawn before the ground, below-horizon ones after it (faint, "seen through" the ground).
     */
    private void drawPredictions(Canvas canvas, boolean aboveHorizon) {
        if (!showPredictions) return;
        float size = 4.5f * dp;
        for (OrbitCatalog.Position p : predictions) {
            if ((p.elevation >= 0) != aboveHorizon) continue;
            if (trackedKeys.contains(p.satellite.key())) continue;
            if (!project(p.elevation, p.azimuth, point)) continue;
            int color = Constellations.color(p.satellite.constellation);
            markerPaint.setStyle(Paint.Style.STROKE);
            markerPaint.setStrokeWidth(1.5f * dp);
            markerPaint.setColor(color);
            markerPaint.setAlpha(aboveHorizon ? 170 : 80);
            path.reset();
            path.moveTo(point[0], point[1] - size);
            path.lineTo(point[0] + size, point[1]);
            path.lineTo(point[0], point[1] + size);
            path.lineTo(point[0] - size, point[1]);
            path.close();
            canvas.drawPath(path, markerPaint);
            if (effectiveFovDeg() <= 100) {
                smallLabelPaint.setColor(color);
                smallLabelPaint.setAlpha(aboveHorizon ? 170 : 80);
                canvas.drawText(predictedLabel(p.satellite), point[0] + size + 3 * dp, point[1] + 3 * dp, smallLabelPaint);
            }
        }
    }

    private static String predictedLabel(OrbitCatalog.Satellite s) {
        return s.svid > 0 ? Constellations.rinexId(s.constellation, s.svid) : "#" + s.tle.noradId;
    }

    /** Dashed orbit of the selected satellite, ±3 h around now, refreshed every minute. */
    private void drawSelectedOrbit(Canvas canvas) {
        OrbitCatalog.Satellite s = selectedCatalogSatellite();
        if (s == null || !haveObserver || catalog == null) return;
        long now = System.currentTimeMillis();
        if (selectedTrack == null || selectedTrackKey != selectedKey || now - selectedTrackAt > TRACK_REFRESH_MS) {
            selectedTrack = catalog.track(s, now - TRACK_HALF_SPAN_MS, now + TRACK_HALF_SPAN_MS, TRACK_STEP_MS,
                    observerLat, observerLon, observerAlt);
            selectedTrackKey = selectedKey;
            selectedTrackAt = now;
        }
        orbitPaint.setColor(Constellations.color(s.constellation));
        orbitPaint.setAlpha(200);
        path.reset();
        boolean pen = false;
        for (float[] p : selectedTrack) {
            if (project(p[0], p[1], point)) {
                if (pen) path.lineTo(point[0], point[1]);
                else path.moveTo(point[0], point[1]);
                pen = true;
            } else {
                pen = false;
            }
        }
        canvas.drawPath(path, orbitPaint);
    }

    private void drawTrails(Canvas canvas) {
        for (SatelliteInfo s : markers) {
            ArrayDeque<float[]> trail = trails.get(s.key());
            if (trail == null || trail.size() < 2) continue;
            trailPaint.setColor(Constellations.color(s.constellation));
            trailPaint.setAlpha(110);
            path.reset();
            boolean pen = false;
            for (float[] p : trail) {
                if (project(p[0], p[1], point)) {
                    if (pen) path.lineTo(point[0], point[1]);
                    else path.moveTo(point[0], point[1]);
                    pen = true;
                } else {
                    pen = false;
                }
            }
            canvas.drawPath(path, trailPaint);
        }
    }

    private void drawSatellites(Canvas canvas) {
        float textOffset = -(labelPaint.descent() + labelPaint.ascent()) / 2f;
        for (SatelliteInfo s : markers) {
            if (!project(s.elevation, s.azimuth, point)) continue;
            int color = Constellations.color(s.constellation);
            float radius = markerRadius(s);
            if (s.usedInFix) {
                markerPaint.setStyle(Paint.Style.FILL);
                markerPaint.setColor(color);
                markerPaint.setAlpha(50);
                canvas.drawCircle(point[0], point[1], radius * 2.2f, markerPaint);
                markerPaint.setAlpha(255);
                canvas.drawCircle(point[0], point[1], radius, markerPaint);
            } else {
                markerPaint.setStyle(Paint.Style.STROKE);
                markerPaint.setStrokeWidth(2 * dp);
                markerPaint.setColor(color);
                markerPaint.setAlpha(s.cn0 > 0 ? 230 : 120);
                canvas.drawCircle(point[0], point[1], radius, markerPaint);
            }
            labelPaint.setColor(color);
            // Leave room for the selection ring.
            canvas.drawText(s.rinexId(), point[0] + radius + 9 * dp, point[1] + textOffset, labelPaint);
        }
    }

    private float markerRadius(SatelliteInfo s) {
        return (5 + Math.max(0f, Math.min(50f, s.cn0)) / 50f * 6) * dp;
    }

    /** Navigation target as a pin on the horizon at its bearing, with name and distance. */
    private void drawTarget(Canvas canvas) {
        if (target == null || !haveObserver) return;
        double distance = Waypoint.distance(observerLat, observerLon, target.latitude, target.longitude);
        double bearing = Waypoint.bearing(observerLat, observerLon, target.latitude, target.longitude);
        double elevation = Double.isNaN(target.altitude) || distance < 1 ? 0
                : Math.toDegrees(Math.atan2(target.altitude - observerAlt, distance));
        if (!project(elevation, bearing, point)) return;
        float x = point[0];
        float y = point[1];
        targetPaint.setStyle(Paint.Style.FILL);
        path.reset();
        path.moveTo(x, y);
        path.lineTo(x - 7 * dp, y - 14 * dp);
        path.lineTo(x + 7 * dp, y - 14 * dp);
        path.close();
        canvas.drawPath(path, targetPaint);
        canvas.drawCircle(x, y - 17 * dp, 7 * dp, targetPaint);
        canvas.drawText(target.name + " · " + Format.distance(distance), x + 11 * dp, y - 13 * dp, targetPaint);
    }

    private void drawHud(Canvas canvas) {
        float x = 12 * dp;
        float y = getHeight() - 12 * dp;
        String mode = arMode ? (Double.isNaN(arFocalPx) ? "  · AR (waiting for camera)"
                : String.format(Locale.ROOT, "  · AR ×%.2f", arScale))
                : followSensor ? "  · compass" : "";
        String view = String.format(Locale.ROOT, "Az %03.0f°  Alt %+.0f°  FOV %.0f°%s",
                lookAzimuth(), lookAltitude(), effectiveFovDeg(), mode);
        canvas.drawText(view, x, y, hudPaint);
        float hintOffset = 0;
        if (arMode && !interacted) {
            hintOffset = hudPaint.getFontSpacing();
            canvas.drawText("Drag: align north · pinch: match camera scale",
                    x, y - hintOffset, hudPaint);
        }
        if (!interacted && !followSensor) {
            canvas.drawText("Drag to look around · pinch to zoom · tap a satellite", x, y - 16 * dp, hudPaint);
        }
        if (followSensor && tracker.isFused()) drawCompassStatus(canvas, x, y - 18 * dp - hintOffset);
    }

    /** Explains why the heading is or is not corrected by the magnetometer. */
    private void drawCompassStatus(Canvas canvas, float x, float baseline) {
        List<String> lines = new ArrayList<>();
        int color;
        float field = tracker.getFieldStrengthUt();
        float expectedField = tracker.getExpectedFieldUt();
        if (tracker.isManualNorth()) {
            color = 0xFF4FC3F7;
            lines.add("North set by hand · toggle Compass to use the magnetometer again");
        } else if (tracker.isCompassTrusted()) {
            color = 0xFF66BB6A;
            lines.add(String.format(Locale.ROOT, "Compass OK · field %.0f µT", field));
        } else {
            color = 0xFFFFA726;
            String expected = Float.isNaN(expectedField) ? "25–65" : String.format(Locale.ROOT, "%.0f", expectedField);
            lines.add(tracker.haveMagnetometerData()
                    ? String.format(Locale.ROOT, "⚠ Compass disturbed: field %.0f µT, expected %s µT", field, expected)
                    : "⚠ No magnetometer data yet");
            lines.add("Remove magnets (case, mount plate), move away from metal,");
            lines.add("wave the phone in a figure 8 · drag sideways to set north");
            if (!tracker.hasHeadingReference()) lines.add("Heading is arbitrary until then (gyro only)");
        }
        hudPaint.setColor(color);
        float lineHeight = hudPaint.getFontSpacing();
        for (int i = lines.size() - 1; i >= 0; i--) {
            canvas.drawText(lines.get(i), x, baseline, hudPaint);
            baseline -= lineHeight;
        }
        hudPaint.setColor(getContext().getColor(R.color.text_secondary));
    }

    private void drawSelection(Canvas canvas) {
        if (selectedKey == Long.MIN_VALUE) return;
        SatelliteInfo s = selectedTracked();
        OrbitCatalog.Satellite orbit = selectedCatalogSatellite();
        OrbitCatalog.Position predicted = selectedPrediction();
        List<String> lines = new ArrayList<>();
        int color;
        if (s != null) {
            color = Constellations.color(s.constellation);
            if (project(s.elevation, s.azimuth, point)) ringAt(canvas, markerRadius(s) + 4 * dp);
            lines.add(title(s.constellation, s.svid));
            String bands = SatelliteMarkers.bands(all, s.key());
            lines.add(Constellations.idLabel(s.constellation, s.svid) + (bands.isEmpty() ? "" : " · " + bands));
            lines.add("C/N0 " + Format.cn0(s.cn0) + (s.usedInFix ? " · used in fix" : " · not used"));
            lines.add(String.format(Locale.ROOT, "Elevation %.1f° · Azimuth %.1f°", s.elevation, s.azimuth));
        } else if (predicted != null) {
            color = Constellations.color(orbit.constellation);
            if (project(predicted.elevation, predicted.azimuth, point)) ringAt(canvas, 8 * dp);
            lines.add(orbit.svid > 0 ? title(orbit.constellation, orbit.svid) : Constellations.name(orbit.constellation));
            lines.add(String.format(Locale.ROOT, "Predicted: el %.1f° · az %.1f°", predicted.elevation, predicted.azimuth));
            lines.add(predicted.elevation < 0 ? "Below the horizon" : "Not tracked by the receiver");
        } else {
            return;
        }
        if (orbit != null) lines.add(orbit.tle.name + " · NORAD " + orbit.tle.noradId);

        float lineHeight = cardTextPaint.getFontSpacing();
        float width = 0;
        for (String l : lines) width = Math.max(width, cardTextPaint.measureText(l));
        float pad = 12 * dp;
        rect.set(pad, pad, pad + width + 2 * pad, pad + lines.size() * lineHeight + 2 * pad);
        canvas.drawRoundRect(rect, 10 * dp, 10 * dp, cardPaint);
        markerPaint.setStyle(Paint.Style.FILL);
        markerPaint.setColor(color);
        canvas.drawRect(rect.left, rect.top + 10 * dp, rect.left + 3 * dp, rect.bottom - 10 * dp, markerPaint);
        float ty = rect.top + pad - cardTextPaint.ascent();
        for (int i = 0; i < lines.size(); i++) {
            cardTextPaint.setFakeBoldText(i == 0);
            canvas.drawText(lines.get(i), rect.left + pad, ty + i * lineHeight, cardTextPaint);
        }
    }

    /** White selection ring around the point last projected into {@link #point}. */
    private void ringAt(Canvas canvas, float radius) {
        markerPaint.setStyle(Paint.Style.STROKE);
        markerPaint.setStrokeWidth(2 * dp);
        markerPaint.setColor(0xFFFFFFFF);
        canvas.drawCircle(point[0], point[1], radius, markerPaint);
    }

    private static String title(int constellation, int svid) {
        String name = Constellations.rinexId(constellation, svid) + "  " + Constellations.name(constellation);
        String operator = Constellations.operator(constellation);
        if (constellation == GnssStatus.CONSTELLATION_SBAS) {
            String service = Constellations.sbasService(svid);
            if (service != null) operator = service;
        }
        return operator.isEmpty() ? name : name + " (" + operator + ")";
    }

    // ---- Touch --------------------------------------------------------------------------------

    @Override
    @SuppressLint("ClickableViewAccessibility") // taps go through selectAt(), which calls performClick()
    public boolean onTouchEvent(MotionEvent event) {
        // Keep the parent (scroll containers) from stealing the drag.
        getParent().requestDisallowInterceptTouchEvent(true);
        boolean handled = scaleGestures.onTouchEvent(event);
        if (!scaleGestures.isInProgress()) handled |= gestures.onTouchEvent(event);
        return handled || super.onTouchEvent(event);
    }

    @Override
    public boolean performClick() {
        return super.performClick();
    }

    private void selectAt(float x, float y) {
        performClick();
        updateCamera();
        long best = Long.MIN_VALUE;
        float bestDistance = 28 * dp;
        for (SatelliteInfo s : markers) {
            if (!project(s.elevation, s.azimuth, point)) continue;
            float d = (float) Math.hypot(point[0] - x, point[1] - y);
            if (d < bestDistance) {
                bestDistance = d;
                best = s.key();
            }
        }
        if (best == Long.MIN_VALUE && showPredictions) {
            for (OrbitCatalog.Position p : predictions) {
                if (!project(p.elevation, p.azimuth, point)) continue;
                float d = (float) Math.hypot(point[0] - x, point[1] - y);
                if (d < bestDistance) {
                    bestDistance = d;
                    best = p.satellite.key();
                }
            }
        }
        selectedKey = best;
        selectedTrack = null;
        invalidate();
    }

    private SatelliteInfo selectedTracked() {
        for (SatelliteInfo s : markers) if (s.key() == selectedKey) return s;
        return null;
    }

    private OrbitCatalog.Position selectedPrediction() {
        for (OrbitCatalog.Position p : predictions) if (p.satellite.key() == selectedKey) return p;
        return null;
    }

    private OrbitCatalog.Satellite selectedCatalogSatellite() {
        if (selectedKey == Long.MIN_VALUE || catalog == null) return null;
        for (OrbitCatalog.Satellite s : catalog.getSatellites()) if (s.key() == selectedKey) return s;
        return null;
    }

    // ---- Helpers ------------------------------------------------------------------------------

    private float lookAzimuth() {
        return normalize((float) Math.toDegrees(Math.atan2(fwd[0], fwd[1])));
    }

    private float lookAltitude() {
        return (float) Math.toDegrees(Math.asin(Math.max(-1, Math.min(1, fwd[2]))));
    }

    private static double dot(double[] a, double[] b) {
        return a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
    }

    private static void normalize(double[] v) {
        double n = Math.sqrt(dot(v, v));
        if (n < 1e-9) return;
        for (int i = 0; i < 3; i++) v[i] /= n;
    }

    private static float normalize(float deg) {
        float r = deg % 360f;
        return r < 0 ? r + 360f : r;
    }

    private static float clamp(float v, float min, float max) {
        return Math.max(min, Math.min(max, v));
    }
}

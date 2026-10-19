package com.vladgba.gpstools.ui;

import android.content.Context;
import android.hardware.GeomagneticField;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;

/**
 * Device attitude in the world frame (ENU, true north), robust to a disturbed magnetometer.
 *
 * <p>Gyro-based GAME_ROTATION_VECTOR gives the attitude; accelerometer + magnetometer only
 * correct its heading, and only while the measured field strength matches Earth's field at the
 * observer (WMM). Vendor ROTATION_VECTOR implementations re-anchor to the magnetometer
 * blindly, so a magnet carried with the phone (case, mount plate) drags the heading back to
 * one direction. Without a gyro it falls back to the platform ROTATION_VECTOR.
 */
public final class OrientationTracker implements SensorEventListener {

    public interface Callback {
        void onOrientationChanged();
    }

    /** Per-sample pull of the gyro heading towards the compass heading (~1 s at 50 Hz). */
    private static final double YAW_CORRECTION_RATE = 0.02;
    private static final float GRAVITY_SMOOTHING = 0.15f;
    /** Compass is trusted when |B| is within this fraction of the expected Earth field. */
    private static final float FIELD_TOLERANCE = 0.35f;
    private static final float FIELD_MIN_UT = 20f;
    private static final float FIELD_MAX_UT = 75f;

    private final SensorManager sensorManager;
    private final Sensor gameRotationSensor;
    private final Sensor accelerometer;
    private final Sensor magnetometer;
    private final Sensor rotationSensor;
    private final boolean ownFusion;
    private final Callback callback;
    private boolean running;

    private final float[] rotation = new float[9];
    private final float[] gameRotation = new float[9];
    private final float[] magRotation = new float[9];
    private final float[] gravity = new float[3];
    private final float[] magnetic = new float[3];
    private boolean haveFrame;
    private boolean haveGravity;
    private boolean haveMagnetic;
    private int magneticAccuracy = SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM;
    private float fieldStrengthUt = Float.NaN;
    private float expectedFieldUt = Float.NaN;
    private float declinationDeg;
    private double yawOffsetRad;
    private boolean haveYawOffset;
    private boolean manualNorth;

    public OrientationTracker(Context context, Callback callback) {
        this.callback = callback;
        sensorManager = (SensorManager) context.getSystemService(Context.SENSOR_SERVICE);
        gameRotationSensor = sensor(Sensor.TYPE_GAME_ROTATION_VECTOR);
        accelerometer = sensor(Sensor.TYPE_ACCELEROMETER);
        magnetometer = sensor(Sensor.TYPE_MAGNETIC_FIELD);
        ownFusion = gameRotationSensor != null && accelerometer != null && magnetometer != null;
        rotationSensor = ownFusion ? null : sensor(Sensor.TYPE_ROTATION_VECTOR);
    }

    private Sensor sensor(int type) {
        return sensorManager != null ? sensorManager.getDefaultSensor(type) : null;
    }

    public boolean isAvailable() {
        return ownFusion || rotationSensor != null;
    }

    /** Whether the heading can be corrected by hand / reported as trusted (own fusion only). */
    public boolean isFused() {
        return ownFusion;
    }

    public void start() {
        if (running || !isAvailable()) return;
        running = true;
        haveFrame = haveGravity = haveMagnetic = false;
        haveYawOffset = false;
        manualNorth = false;
        if (ownFusion) {
            sensorManager.registerListener(this, gameRotationSensor, SensorManager.SENSOR_DELAY_GAME);
            sensorManager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_GAME);
            sensorManager.registerListener(this, magnetometer, SensorManager.SENSOR_DELAY_GAME);
        } else {
            sensorManager.registerListener(this, rotationSensor, SensorManager.SENSOR_DELAY_GAME);
        }
    }

    public void stop() {
        if (!running) return;
        running = false;
        sensorManager.unregisterListener(this);
    }

    public boolean isRunning() {
        return running;
    }

    /** Magnetic declination and expected field strength for the observer. */
    public void setObserver(double lat, double lon, double altitudeM, long timeMs) {
        GeomagneticField field = new GeomagneticField((float) lat, (float) lon, (float) altitudeM, timeMs);
        declinationDeg = field.getDeclination();
        expectedFieldUt = field.getFieldStrength() / 1000f; // nT -> µT
    }

    /** Rotates north by hand (radians, positive = scene turns counter-clockwise seen from above). */
    public void adjustNorth(double deltaRad) {
        yawOffsetRad += deltaRad;
        haveYawOffset = true;
        manualNorth = true;
    }

    // ---- Results ------------------------------------------------------------------------------

    public boolean hasFrame() {
        return haveFrame;
    }

    /**
     * Row-major 3x3 matrix whose columns are the device axes (x right, y up the screen, z out of
     * the screen) in the world frame (x east, y true north, z up). Not remapped for display
     * rotation.
     */
    public float[] getRotation() {
        return rotation;
    }

    public boolean isManualNorth() {
        return manualNorth;
    }

    public boolean hasHeadingReference() {
        return haveYawOffset || !ownFusion;
    }

    public boolean haveMagnetometerData() {
        return haveMagnetic;
    }

    public float getFieldStrengthUt() {
        return fieldStrengthUt;
    }

    public float getExpectedFieldUt() {
        return expectedFieldUt;
    }

    /**
     * Pointing azimuth in degrees (true north): the top edge of the phone when it lies flat,
     * the back camera direction when it is held up.
     */
    public float getHeadingDegrees() {
        double x;
        double y;
        if (Math.abs(rotation[8]) > 0.7) { // screen facing up or down: use the device Y axis
            x = rotation[1];
            y = rotation[4];
        } else {                           // held up: camera looks along -Z
            x = -rotation[2];
            y = -rotation[5];
        }
        double deg = Math.toDegrees(Math.atan2(x, y));
        return (float) ((deg % 360 + 360) % 360);
    }

    /**
     * The magnetometer is only believed when its field strength matches Earth's field here
     * and Android does not flag it as unreliable.
     */
    public boolean isCompassTrusted() {
        if (!ownFusion) return true;
        if (!haveMagnetic || magneticAccuracy == SensorManager.SENSOR_STATUS_UNRELIABLE) return false;
        if (!Float.isNaN(expectedFieldUt)) {
            return Math.abs(fieldStrengthUt - expectedFieldUt) <= expectedFieldUt * FIELD_TOLERANCE;
        }
        return fieldStrengthUt >= FIELD_MIN_UT && fieldStrengthUt <= FIELD_MAX_UT;
    }

    // ---- Sensor events ------------------------------------------------------------------------

    @Override
    public void onSensorChanged(SensorEvent event) {
        switch (event.sensor.getType()) {
            case Sensor.TYPE_ACCELEROMETER:
                for (int i = 0; i < 3; i++) {
                    gravity[i] = haveGravity ? gravity[i] + (event.values[i] - gravity[i]) * GRAVITY_SMOOTHING
                            : event.values[i];
                }
                haveGravity = true;
                return;
            case Sensor.TYPE_MAGNETIC_FIELD:
                System.arraycopy(event.values, 0, magnetic, 0, 3);
                haveMagnetic = true;
                fieldStrengthUt = (float) Math.sqrt(magnetic[0] * magnetic[0]
                        + magnetic[1] * magnetic[1] + magnetic[2] * magnetic[2]);
                return;
            case Sensor.TYPE_GAME_ROTATION_VECTOR:
                fuseHeading(event.values);
                break;
            default:
                SensorManager.getRotationMatrixFromVector(rotation, event.values);
                break;
        }
        rotateToTrueNorth();
        haveFrame = true;
        callback.onOrientationChanged();
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
        if (sensor.getType() == Sensor.TYPE_MAGNETIC_FIELD) magneticAccuracy = accuracy;
    }

    /** rotation = Rz(yawOffset) · gameRotation, with yawOffset pulled towards the compass. */
    private void fuseHeading(float[] rotationVector) {
        SensorManager.getRotationMatrixFromVector(gameRotation, rotationVector);
        if (!manualNorth && isCompassTrusted() && haveGravity
                && SensorManager.getRotationMatrix(magRotation, null, gravity, magnetic)) {
            // Best-fit rotation about world up between the two frames: D = Rmag · Rgameᵀ.
            double s = 0;
            double c = 0;
            for (int k = 0; k < 3; k++) {
                s += magRotation[3 + k] * gameRotation[k] - magRotation[k] * gameRotation[3 + k];
                c += magRotation[k] * gameRotation[k] + magRotation[3 + k] * gameRotation[3 + k];
            }
            double target = Math.atan2(s, c);
            if (!haveYawOffset) {
                yawOffsetRad = target;
                haveYawOffset = true;
            } else {
                yawOffsetRad += wrap(target - yawOffsetRad) * YAW_CORRECTION_RATE;
            }
        }
        double cos = Math.cos(yawOffsetRad);
        double sin = Math.sin(yawOffsetRad);
        for (int col = 0; col < 3; col++) {
            float x = gameRotation[col];
            float y = gameRotation[3 + col];
            rotation[col] = (float) (cos * x - sin * y);
            rotation[3 + col] = (float) (sin * x + cos * y);
            rotation[6 + col] = gameRotation[6 + col];
        }
    }

    /** Magnetic azimuth + declination (east positive) = true azimuth, applied to every axis. */
    private void rotateToTrueNorth() {
        double d = Math.toRadians(declinationDeg);
        double cos = Math.cos(d);
        double sin = Math.sin(d);
        for (int col = 0; col < 3; col++) {
            float x = rotation[col];
            float y = rotation[3 + col];
            rotation[col] = (float) (x * cos + y * sin);
            rotation[3 + col] = (float) (y * cos - x * sin);
        }
    }

    private static double wrap(double rad) {
        while (rad > Math.PI) rad -= 2 * Math.PI;
        while (rad < -Math.PI) rad += 2 * Math.PI;
        return rad;
    }
}

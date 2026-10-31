package x.vladgba.gps.gnss;

import android.location.Location;

/**
 * Survey-style position averaging. Fixes are converted to east/north/up offsets (metres) from
 * the first accepted fix on a local tangent plane, which is exact enough over the few metres a
 * stationary receiver wanders. Statistics are accumulated incrementally (Welford), the points
 * are kept (bounded) for the scatter plot.
 */
public final class PositionAverager {

    public static final int MAX_POINTS = 20_000;
    private static final double EARTH_RADIUS_M = 6_371_008.8;

    private boolean running;
    private float maxAccuracyM = Float.POSITIVE_INFINITY;
    private long startedAtMs;
    private long activeMs;
    private long lastFixElapsedNanos = Long.MIN_VALUE;

    private boolean haveReference;
    private double refLat;
    private double refLon;
    private double refAlt;
    private double metersPerDegLon;

    private int count;
    private int repeated;
    private double lastLat = Double.NaN;
    private double lastLon = Double.NaN;
    private int altitudeCount;
    private int rejected;
    private double meanE;
    private double meanN;
    private double meanU;
    private double m2E;
    private double m2N;
    private double m2U;

    private final float[] pointsE = new float[MAX_POINTS];
    private final float[] pointsN = new float[MAX_POINTS];
    private int points;

    public void start(long nowMs) {
        if (running) return;
        running = true;
        startedAtMs = nowMs;
    }

    public void pause(long nowMs) {
        if (!running) return;
        activeMs += nowMs - startedAtMs;
        running = false;
    }

    public void reset() {
        running = false;
        activeMs = 0;
        haveReference = false;
        count = altitudeCount = rejected = points = repeated = 0;
        meanE = meanN = meanU = m2E = m2N = m2U = 0;
        lastFixElapsedNanos = Long.MIN_VALUE;
    }

    /** Fixes less accurate than this (reported 68% radius) are ignored. */
    public void setMaxAccuracy(float meters) {
        maxAccuracyM = meters;
    }

    public float getMaxAccuracy() {
        return maxAccuracyM;
    }

    public void onFix(Location loc) {
        if (!running || loc.getElapsedRealtimeNanos() == lastFixElapsedNanos) return;
        lastFixElapsedNanos = loc.getElapsedRealtimeNanos();
        if (loc.hasAccuracy() && loc.getAccuracy() > maxAccuracyM) {
            rejected++;
            return;
        }
        if (!haveReference) {
            haveReference = true;
            refLat = loc.getLatitude();
            refLon = loc.getLongitude();
            refAlt = loc.hasAltitude() ? loc.getAltitude() : 0;
            metersPerDegLon = Math.toRadians(1) * EARTH_RADIUS_M * Math.cos(Math.toRadians(refLat));
        }
        double e = (loc.getLongitude() - refLon) * metersPerDegLon;
        double n = (loc.getLatitude() - refLat) * Math.toRadians(1) * EARTH_RADIUS_M;
        if (count > 0 && loc.getLatitude() == lastLat && loc.getLongitude() == lastLon) repeated++;
        lastLat = loc.getLatitude();
        lastLon = loc.getLongitude();
        count++;
        double dE = e - meanE;
        meanE += dE / count;
        m2E += dE * (e - meanE);
        double dN = n - meanN;
        meanN += dN / count;
        m2N += dN * (n - meanN);
        if (loc.hasAltitude()) {
            double u = loc.getAltitude() - refAlt;
            altitudeCount++;
            double dU = u - meanU;
            meanU += dU / altitudeCount;
            m2U += dU * (u - meanU);
        }
        if (points < MAX_POINTS) {
            pointsE[points] = (float) e;
            pointsN[points] = (float) n;
            points++;
        }
    }

    // ---- Results ------------------------------------------------------------------------------

    public boolean isRunning() {
        return running;
    }

    public int getCount() {
        return count;
    }

    public int getRejected() {
        return rejected;
    }

    public long getDurationMs(long nowMs) {
        return activeMs + (running ? nowMs - startedAtMs : 0);
    }

    /**
     * Many receivers freeze the reported position while stationary ("static hold"), so most
     * fixes are exact repeats and the spread looks unrealistically small.
     */
    public boolean isStaticHoldSuspected() {
        return count >= 10 && repeated >= 0.7 * count;
    }

    public boolean hasResult() {
        return count > 0;
    }

    public double getMeanLatitude() {
        return refLat + meanN / (Math.toRadians(1) * EARTH_RADIUS_M);
    }

    public double getMeanLongitude() {
        return refLon + meanE / metersPerDegLon;
    }

    /** Mean ellipsoidal altitude, NaN when no fix had one. */
    public double getMeanAltitude() {
        return altitudeCount == 0 ? Double.NaN : refAlt + meanU;
    }

    public double getStdEast() {
        return count < 2 ? Double.NaN : Math.sqrt(m2E / (count - 1));
    }

    public double getStdNorth() {
        return count < 2 ? Double.NaN : Math.sqrt(m2N / (count - 1));
    }

    public double getStdUp() {
        return altitudeCount < 2 ? Double.NaN : Math.sqrt(m2U / (altitudeCount - 1));
    }

    /** Circular error probable (50%), usual approximation 0.59·(σE + σN). */
    public double getCep50() {
        return 0.59 * (getStdEast() + getStdNorth());
    }

    /** Twice the distance RMS, ~95% of fixes inside. */
    public double get2Drms() {
        return 2 * Math.sqrt(sq(getStdEast()) + sq(getStdNorth()));
    }

    public double getMeanEastOffset() {
        return meanE;
    }

    public double getMeanNorthOffset() {
        return meanN;
    }

    public int getPointCount() {
        return points;
    }

    public float pointEast(int i) {
        return pointsE[i];
    }

    public float pointNorth(int i) {
        return pointsN[i];
    }

    private static double sq(double v) {
        return v * v;
    }
}

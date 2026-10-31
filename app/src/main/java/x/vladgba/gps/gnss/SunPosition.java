package x.vladgba.gps.gnss;

/**
 * Approximate topocentric Sun position (USNO low-precision formulas, about 0.1° accuracy over
 * this century; refraction ignored). Good enough as an orientation reference in the sky view.
 */
public final class SunPosition {

    private SunPosition() {
    }

    /** @return {altitude, azimuth} in degrees, azimuth clockwise from true north */
    public static double[] altAz(long epochMs, double latDeg, double lonDeg) {
        double d = epochMs / 86_400_000.0 + 2440587.5 - 2451545.0; // days since J2000.0
        double g = Math.toRadians(normalize(357.529 + 0.98560028 * d));
        double q = normalize(280.459 + 0.98564736 * d);
        double lambda = Math.toRadians(q + 1.915 * Math.sin(g) + 0.020 * Math.sin(2 * g));
        double epsilon = Math.toRadians(23.439 - 0.00000036 * d);

        double ra = Math.atan2(Math.cos(epsilon) * Math.sin(lambda), Math.cos(lambda));
        double dec = Math.asin(Math.sin(epsilon) * Math.sin(lambda));

        double gmstHours = 18.697374558 + 24.06570982441908 * d;
        double lst = Math.toRadians(normalize(gmstHours * 15.0 + lonDeg));
        double hourAngle = lst - ra;

        double lat = Math.toRadians(latDeg);
        double alt = Math.asin(Math.sin(lat) * Math.sin(dec) + Math.cos(lat) * Math.cos(dec) * Math.cos(hourAngle));
        double az = Math.atan2(-Math.sin(hourAngle),
                Math.tan(dec) * Math.cos(lat) - Math.sin(lat) * Math.cos(hourAngle));
        return new double[]{Math.toDegrees(alt), normalize(Math.toDegrees(az))};
    }

    private static double normalize(double deg) {
        double r = deg % 360.0;
        return r < 0 ? r + 360.0 : r;
    }
}

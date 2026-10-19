package com.vladgba.gpstools.orbit;

/**
 * Lightweight orbit propagation from TLE mean elements: SGP4's element recovery (Kozai to
 * Brouwer mean motion) plus J2 secular drift of the node, perigee and mean anomaly, then a
 * Keplerian position. It skips drag (irrelevant at GNSS altitudes) and lunar/solar terms, which
 * keeps it to a page of code; for GNSS orbits within a few days of the TLE epoch the direction
 * error seen from the ground is a fraction of a degree, plenty for sky views and rise/set times.
 *
 * <p>Output frame is TEME (km), converted to Earth-fixed with GMST.
 */
public final class OrbitPropagator {

    /** sqrt(GM) in Earth radii^1.5 per minute (WGS-72, as used by SGP4). */
    private static final double XKE = 0.0743669161;
    private static final double J2 = 1.082616e-3;
    private static final double EARTH_RADIUS_KM = 6378.135;
    private static final double TWO_PI = 2 * Math.PI;

    private final long epochMs;
    private final double inclination;
    private final double eccentricity;
    private final double semiMajorAxis; // Earth radii
    private final double raan0;
    private final double argPerigee0;
    private final double meanAnomaly0;
    private final double raanRate;      // rad/min
    private final double argPerigeeRate;
    private final double meanMotion;    // rad/min, including J2 drift

    public OrbitPropagator(Tle tle) {
        epochMs = tle.epochMs;
        inclination = Math.toRadians(tle.inclinationDeg);
        eccentricity = tle.eccentricity;
        raan0 = Math.toRadians(tle.raanDeg);
        argPerigee0 = Math.toRadians(tle.argPerigeeDeg);
        meanAnomaly0 = Math.toRadians(tle.meanAnomalyDeg);

        double n0 = tle.meanMotion * TWO_PI / 1440.0;
        double cosI = Math.cos(inclination);
        double theta2 = cosI * cosI;
        double beta2 = 1 - eccentricity * eccentricity;
        double k2 = 0.5 * J2;
        double a1 = Math.pow(XKE / n0, 2.0 / 3.0);
        double d1 = 1.5 * k2 * (3 * theta2 - 1) / (a1 * a1 * Math.pow(beta2, 1.5));
        double a0 = a1 * (1 - d1 / 3 - d1 * d1 - 134.0 / 81.0 * d1 * d1 * d1);
        double d0 = 1.5 * k2 * (3 * theta2 - 1) / (a0 * a0 * Math.pow(beta2, 1.5));
        double n = n0 / (1 + d0);
        semiMajorAxis = a0 / (1 - d0);

        double p = semiMajorAxis * beta2;
        double factor = 1.5 * J2 * n / (p * p);
        raanRate = -factor * cosI;
        argPerigeeRate = 0.5 * factor * (5 * theta2 - 1);
        meanMotion = n + 0.5 * factor * Math.sqrt(beta2) * (3 * theta2 - 1);
    }

    /** Earth-fixed position (km) at the given UTC time. */
    public void positionEcef(long timeMs, double[] out) {
        double t = (timeMs - epochMs) / 60_000.0;
        double raan = raan0 + raanRate * t;
        double argp = argPerigee0 + argPerigeeRate * t;
        double m = (meanAnomaly0 + meanMotion * t) % TWO_PI;

        double e = eccentricity;
        double ea = m;
        for (int i = 0; i < 10; i++) {
            double delta = (ea - e * Math.sin(ea) - m) / (1 - e * Math.cos(ea));
            ea -= delta;
            if (Math.abs(delta) < 1e-10) break;
        }
        double nu = 2 * Math.atan2(Math.sqrt(1 + e) * Math.sin(ea / 2), Math.sqrt(1 - e) * Math.cos(ea / 2));
        double r = semiMajorAxis * (1 - e * Math.cos(ea)) * EARTH_RADIUS_KM;
        double xo = r * Math.cos(nu);
        double yo = r * Math.sin(nu);

        double cO = Math.cos(raan);
        double sO = Math.sin(raan);
        double cw = Math.cos(argp);
        double sw = Math.sin(argp);
        double ci = Math.cos(inclination);
        double si = Math.sin(inclination);
        double x = xo * (cO * cw - sO * sw * ci) - yo * (cO * sw + sO * cw * ci);
        double y = xo * (sO * cw + cO * sw * ci) + yo * (cO * cw * ci - sO * sw);
        double z = xo * (sw * si) + yo * (cw * si);

        double gmst = gmst(timeMs);
        double cg = Math.cos(gmst);
        double sg = Math.sin(gmst);
        out[0] = cg * x + sg * y;
        out[1] = -sg * x + cg * y;
        out[2] = z;
    }

    /** Greenwich mean sidereal time (IAU-82, as in SGP4's gstime), radians. */
    static double gmst(long timeMs) {
        double jd = timeMs / 86_400_000.0 + 2440587.5;
        double tut1 = (jd - 2451545.0) / 36525.0;
        double seconds = -6.2e-6 * tut1 * tut1 * tut1 + 0.093104 * tut1 * tut1
                + (876600.0 * 3600 + 8640184.812866) * tut1 + 67310.54841;
        double rad = (seconds * Math.PI / 180.0 / 240.0) % TWO_PI;
        return rad < 0 ? rad + TWO_PI : rad;
    }

    /** WGS-84 geodetic to Earth-fixed (km). */
    public static void observerEcef(double latDeg, double lonDeg, double altM, double[] out) {
        double a = 6378.137;
        double f = 1 / 298.257223563;
        double e2 = f * (2 - f);
        double lat = Math.toRadians(latDeg);
        double lon = Math.toRadians(lonDeg);
        double h = altM / 1000.0;
        double sinLat = Math.sin(lat);
        double n = a / Math.sqrt(1 - e2 * sinLat * sinLat);
        out[0] = (n + h) * Math.cos(lat) * Math.cos(lon);
        out[1] = (n + h) * Math.cos(lat) * Math.sin(lon);
        out[2] = (n * (1 - e2) + h) * sinLat;
    }

    /** Elevation and azimuth (degrees) of an Earth-fixed point seen from the observer. */
    public static void lookAngles(double[] sat, double[] obs, double latDeg, double lonDeg, float[] out) {
        double dx = sat[0] - obs[0];
        double dy = sat[1] - obs[1];
        double dz = sat[2] - obs[2];
        double lat = Math.toRadians(latDeg);
        double lon = Math.toRadians(lonDeg);
        double sLat = Math.sin(lat);
        double cLat = Math.cos(lat);
        double sLon = Math.sin(lon);
        double cLon = Math.cos(lon);
        double e = -sLon * dx + cLon * dy;
        double n = -sLat * cLon * dx - sLat * sLon * dy + cLat * dz;
        double u = cLat * cLon * dx + cLat * sLon * dy + sLat * dz;
        double range = Math.sqrt(e * e + n * n + u * u);
        out[0] = (float) Math.toDegrees(Math.asin(u / range));
        double az = Math.toDegrees(Math.atan2(e, n));
        out[1] = (float) (az < 0 ? az + 360 : az);
    }
}

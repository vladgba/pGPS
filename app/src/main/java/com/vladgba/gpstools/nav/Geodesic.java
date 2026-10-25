package com.vladgba.gpstools.nav;

import java.util.List;

/**
 * Geodesic and surveying calculations on a sphere / local tangent plane:
 * direct projection (point + azimuth + distance -> destination), acoustic flash-to-bang
 * distance, and multi-bearing intersection (triangulation) with residual spread.
 */
public final class Geodesic {

    private static final double EARTH_RADIUS_M = 6_371_008.8;

    private Geodesic() {
    }

    /** A bearing ray cast from a known observer position. */
    public static final class Ray {
        public final String label;
        public final double latitude;
        public final double longitude;
        /** Azimuth in degrees clockwise from true north [0, 360). */
        public final double azimuthDeg;

        public Ray(String label, double latitude, double longitude, double azimuthDeg) {
            this.label = label;
            this.latitude = latitude;
            this.longitude = longitude;
            this.azimuthDeg = ((azimuthDeg % 360) + 360) % 360;
        }
    }

    /** Result of intersecting two or more bearing rays. */
    public static final class Intersection {
        public final double latitude;
        public final double longitude;
        /** RMS perpendicular miss distance in metres (0 for 2 rays, spread indicator for 3+). */
        public final double rmsMissM;
        /** Largest acute crossing angle in degrees (warns when all bearings are nearly parallel). */
        public final double maxAngleDeg;

        public Intersection(double latitude, double longitude, double rmsMissM, double maxAngleDeg) {
            this.latitude = latitude;
            this.longitude = longitude;
            this.rmsMissM = rmsMissM;
            this.maxAngleDeg = maxAngleDeg;
        }
    }

    /**
     * Speed of sound in dry air (m/s) as a function of ambient temperature in °C:
     * c = 331.3 * sqrt(1 + T / 273.15).
     */
    public static double speedOfSound(double temperatureCelsius) {
        if (!Double.isFinite(temperatureCelsius)) return Double.NaN;
        double kelvin = Math.max(150.0, 273.15 + temperatureCelsius);
        return 331.3 * Math.sqrt(kelvin / 273.15);
    }

    /** Acoustic distance in metres from a flash-to-bang time interval (seconds) and air temperature (°C). */
    public static double acousticDistance(double elapsedSeconds, double temperatureCelsius) {
        if (elapsedSeconds <= 0 || !Double.isFinite(elapsedSeconds)) return 0;
        double distance = elapsedSeconds * speedOfSound(temperatureCelsius);
        return Double.isFinite(distance) ? distance : 0;
    }

    /**
     * Projects a destination point from (lat, lon) along initial bearing {@code azimuthDeg}
     * (degrees clockwise from true north) for {@code distanceM} metres on the sphere.
     *
     * @return {@code {latitude, longitude}} in degrees
     */
    public static double[] destination(double lat, double lon, double azimuthDeg, double distanceM) {
        if (!validPosition(lat, lon) || !Double.isFinite(azimuthDeg)
                || !Double.isFinite(distanceM) || distanceM < 0) {
            throw new IllegalArgumentException("Expected finite coordinates, bearing and non-negative distance");
        }
        double delta = distanceM / EARTH_RADIUS_M;
        double theta = Math.toRadians(azimuthDeg % 360);
        double phi1 = Math.toRadians(lat);
        double lam1 = Math.toRadians(lon);

        double sinPhi1 = Math.sin(phi1);
        double cosPhi1 = Math.cos(phi1);
        double sinDelta = Math.sin(delta);
        double cosDelta = Math.cos(delta);

        double sinPhi2 = sinPhi1 * cosDelta + cosPhi1 * sinDelta * Math.cos(theta);
        double phi2 = Math.asin(Math.max(-1.0, Math.min(1.0, sinPhi2)));
        double y = Math.sin(theta) * sinDelta * cosPhi1;
        double x = cosDelta - sinPhi1 * Math.sin(phi2);
        double lam2 = lam1 + Math.atan2(y, x);

        double lon2 = wrapLongitude(Math.toDegrees(lam2));
        return new double[]{Math.toDegrees(phi2), lon2};
    }

    /**
     * Computes the best-fit intersection of 2 or more bearing rays in a local East-North
     * tangent plane centred on the centroid of the observer stations.
     *
     * <p>For 2 rays, requires forward intersection (both rays pointing toward the crossing).
     * For 3+ rays, solves the least-squares point minimizing perpendicular distances to all lines
     * and checks that the solution lies predominantly ahead of the rays.
     *
     * @return the {@link Intersection}, or {@code null} if rays are parallel, diverge, or fewer than 2
     */
    public static Intersection triangulate(List<Ray> rays) {
        if (rays == null || rays.size() < 2) return null;
        int n = rays.size();

        double refLat = 0;
        if (rays.get(0) == null) return null;
        double anchorLon = rays.get(0).longitude;
        double refLon = 0;
        for (Ray r : rays) {
            if (r == null || !validPosition(r.latitude, r.longitude)
                    || !Double.isFinite(r.azimuthDeg)) return null;
            refLat += r.latitude;
            refLon += wrapLongitude(r.longitude - anchorLon);
        }
        refLat /= n;
        refLon = wrapLongitude(anchorLon + refLon / n);

        double kx = Math.PI / 180.0 * EARTH_RADIUS_M * Math.cos(Math.toRadians(refLat));
        double ky = Math.PI / 180.0 * EARTH_RADIUS_M;
        if (Math.abs(kx) < 1e-3) return null;

        double[] px = new double[n];
        double[] py = new double[n];
        double[] ux = new double[n];
        double[] uy = new double[n];

        for (int i = 0; i < n; i++) {
            Ray r = rays.get(i);
            px[i] = wrapLongitude(r.longitude - refLon) * kx;
            py[i] = (r.latitude - refLat) * ky;
            double rad = Math.toRadians(r.azimuthDeg);
            ux[i] = Math.sin(rad); // East
            uy[i] = Math.cos(rad); // North
        }

        double maxAngleDeg = 0;
        for (int i = 0; i < n; i++) {
            for (int j = i + 1; j < n; j++) {
                double dot = Math.max(-1.0, Math.min(1.0, ux[i] * ux[j] + uy[i] * uy[j]));
                double angle = Math.toDegrees(Math.acos(dot));
                if (angle > 90.0) angle = 180.0 - angle;
                if (angle > maxAngleDeg) maxAngleDeg = angle;
            }
        }
        if (maxAngleDeg < 0.5) return null; // Virtually parallel

        double ix;
        double iy;
        if (n == 2) {
            // Exact 2-ray forward intersection: p0 + t0 * u0 = p1 + t1 * u1
            double det = ux[0] * (-uy[1]) - uy[0] * (-ux[1]);
            if (Math.abs(det) < 1e-7) return null;
            double dx = px[1] - px[0];
            double dy = py[1] - py[0];
            double t0 = (dx * (-uy[1]) - dy * (-ux[1])) / det;
            double t1 = (ux[0] * dy - uy[0] * dx) / det;
            if (t0 <= 0 || t1 <= 0) return null; // Rays diverge or cross behind an observer
            ix = px[0] + t0 * ux[0];
            iy = py[0] + t0 * uy[0];
        } else {
            // Least-squares intersection of N lines: sum (n_i * n_i^T) X = sum (n_i * n_i^T) p_i
            // where normal n_i = (-uy[i], ux[i]).
            double a00 = 0;
            double a01 = 0;
            double a11 = 0;
            double b0 = 0;
            double b1 = 0;
            for (int i = 0; i < n; i++) {
                double nx = -uy[i];
                double ny = ux[i];
                double w00 = nx * nx;
                double w01 = nx * ny;
                double w11 = ny * ny;
                a00 += w00;
                a01 += w01;
                a11 += w11;
                double np = nx * px[i] + ny * py[i];
                b0 += nx * np;
                b1 += ny * np;
            }
            double det = a00 * a11 - a01 * a01;
            if (Math.abs(det) < 1e-7) return null;
            ix = (a11 * b0 - a01 * b1) / det;
            iy = (a00 * b1 - a01 * b0) / det;

            int forwardCount = 0;
            for (int i = 0; i < n; i++) {
                double t = (ix - px[i]) * ux[i] + (iy - py[i]) * uy[i];
                if (t > 0) forwardCount++;
            }
            if (forwardCount <= n / 2) return null;
        }

        double sumSqMiss = 0;
        for (int i = 0; i < n; i++) {
            double dx = ix - px[i];
            double dy = iy - py[i];
            double t = dx * ux[i] + dy * uy[i];
            double missX = dx - Math.max(0, t) * ux[i];
            double missY = dy - Math.max(0, t) * uy[i];
            sumSqMiss += missX * missX + missY * missY;
        }
        double rmsMiss = Math.sqrt(sumSqMiss / n);

        double lat = refLat + iy / ky;
        double lon = wrapLongitude(refLon + ix / kx);
        if (!validPosition(lat, lon) || !Double.isFinite(rmsMiss)) return null;
        return new Intersection(lat, lon, rmsMiss, maxAngleDeg);
    }

    /** Signed shortest longitude delta, also suitable for a local map's east coordinate. */
    public static double wrapLongitude(double longitude) {
        return ((longitude % 360 + 540) % 360) - 180;
    }

    private static boolean validPosition(double latitude, double longitude) {
        return Double.isFinite(latitude) && Double.isFinite(longitude)
                && Math.abs(latitude) <= 90 && Math.abs(longitude) <= 180;
    }
}

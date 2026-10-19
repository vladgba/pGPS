package com.vladgba.gpstools.coords;

/**
 * Transverse Mercator projection with Krüger's series to n³ (millimetre accuracy within a few
 * degrees of the central meridian). Pure Java so it can be tested off-device.
 */
public final class TransverseMercator {

    private final double k0;
    private final double lon0;
    private final double falseEasting;
    private final double falseNorthing;
    private final double scaleA;   // k0 · A (rectifying radius)
    private final double northingAtLat0;
    private final double[] alpha;
    private final double[] beta;
    private final double[] delta;
    private final double eccTerm;  // 2√n / (1 + n)

    /**
     * @param a          semi-major axis (m)
     * @param f          flattening
     * @param k0         central scale factor
     * @param lat0Deg    latitude of the true origin
     * @param lon0Deg    central meridian
     */
    public TransverseMercator(double a, double f, double k0, double lat0Deg, double lon0Deg,
                              double falseEasting, double falseNorthing) {
        this.k0 = k0;
        this.lon0 = Math.toRadians(lon0Deg);
        this.falseEasting = falseEasting;
        this.falseNorthing = falseNorthing;
        double n = f / (2 - f);
        double n2 = n * n;
        double n3 = n2 * n;
        double bigA = a / (1 + n) * (1 + n2 / 4 + n2 * n2 / 64);
        scaleA = k0 * bigA;
        alpha = new double[]{n / 2 - 2 * n2 / 3 + 5 * n3 / 16, 13 * n2 / 48 - 3 * n3 / 5, 61 * n3 / 240};
        beta = new double[]{n / 2 - 2 * n2 / 3 + 37 * n3 / 96, n2 / 48 + n3 / 15, 17 * n3 / 480};
        delta = new double[]{2 * n - 2 * n2 / 3 - 2 * n3, 7 * n2 / 3 - 8 * n3 / 5, 56 * n3 / 15};
        eccTerm = 2 * Math.sqrt(n) / (1 + n);
        northingAtLat0 = lat0Deg == 0 ? 0 : rawNorthing(Math.toRadians(lat0Deg), 0);
    }

    /** @return {easting, northing} in metres */
    public double[] forward(double latDeg, double lonDeg) {
        double lat = Math.toRadians(latDeg);
        double dLon = Math.toRadians(lonDeg) - lon0;
        double t = Math.sinh(atanh(Math.sin(lat)) - eccTerm * atanh(eccTerm * Math.sin(lat)));
        double xiP = Math.atan2(t, Math.cos(dLon));
        double etaP = atanh(Math.sin(dLon) / Math.sqrt(1 + t * t));
        double xi = xiP;
        double eta = etaP;
        for (int j = 1; j <= 3; j++) {
            xi += alpha[j - 1] * Math.sin(2 * j * xiP) * Math.cosh(2 * j * etaP);
            eta += alpha[j - 1] * Math.cos(2 * j * xiP) * Math.sinh(2 * j * etaP);
        }
        return new double[]{falseEasting + scaleA * eta, falseNorthing + scaleA * xi - northingAtLat0};
    }

    /** @return {latitude, longitude} in degrees */
    public double[] inverse(double easting, double northing) {
        double xi = (northing - falseNorthing + northingAtLat0) / scaleA;
        double eta = (easting - falseEasting) / scaleA;
        double xiP = xi;
        double etaP = eta;
        for (int j = 1; j <= 3; j++) {
            xiP -= beta[j - 1] * Math.sin(2 * j * xi) * Math.cosh(2 * j * eta);
            etaP -= beta[j - 1] * Math.cos(2 * j * xi) * Math.sinh(2 * j * eta);
        }
        double chi = Math.asin(Math.sin(xiP) / Math.cosh(etaP));
        double lat = chi;
        for (int j = 1; j <= 3; j++) lat += delta[j - 1] * Math.sin(2 * j * chi);
        double lon = lon0 + Math.atan2(Math.sinh(etaP), Math.cos(xiP));
        return new double[]{Math.toDegrees(lat), Math.toDegrees(lon)};
    }

    private double rawNorthing(double lat, double dLon) {
        double t = Math.sinh(atanh(Math.sin(lat)) - eccTerm * atanh(eccTerm * Math.sin(lat)));
        double xiP = Math.atan2(t, Math.cos(dLon));
        double etaP = atanh(Math.sin(dLon) / Math.sqrt(1 + t * t));
        double xi = xiP;
        for (int j = 1; j <= 3; j++) xi += alpha[j - 1] * Math.sin(2 * j * xiP) * Math.cosh(2 * j * etaP);
        return scaleA * xi;
    }

    public double getScaleFactor() {
        return k0;
    }

    private static double atanh(double x) {
        return 0.5 * Math.log((1 + x) / (1 - x));
    }
}

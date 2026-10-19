package com.vladgba.gpstools.coords;

import java.util.Locale;

/**
 * Ordnance Survey National Grid (OSGB36 / Airy 1830 / British National Grid). WGS84 is shifted
 * to OSGB36 with the standard 7-parameter Helmert transform, which is good to about 3–5 m (the
 * centimetre-level OSTN15 grid is a large dataset and not bundled).
 */
public final class BritishGrid {

    private static final double AIRY_A = 6377563.396;
    private static final double AIRY_B = 6356256.909;
    private static final TransverseMercator NATIONAL_GRID = new TransverseMercator(
            AIRY_A, (AIRY_A - AIRY_B) / AIRY_A, 0.9996012717, 49, -2, 400_000, -100_000);

    // WGS84 -> OSGB36 Helmert parameters (Ordnance Survey).
    private static final double TX = -446.448;
    private static final double TY = 125.157;
    private static final double TZ = -542.060;
    private static final double S = 20.4894e-6;
    private static final double RX = Math.toRadians(-0.1502 / 3600);
    private static final double RY = Math.toRadians(-0.2470 / 3600);
    private static final double RZ = Math.toRadians(-0.8421 / 3600);

    public final double easting;
    public final double northing;

    private BritishGrid(double easting, double northing) {
        this.easting = easting;
        this.northing = northing;
    }

    /** @return null outside the grid's area (Great Britain and surroundings) */
    public static BritishGrid fromWgs84(double lat, double lon) {
        if (lat < 49 || lat > 61.5 || lon < -9.5 || lon > 2.5) return null;
        double[] osgb = wgs84ToOsgb36(lat, lon);
        return fromOsgb36(osgb[0], osgb[1]);
    }

    /** National Grid from OSGB36 latitude/longitude (no datum shift). */
    static BritishGrid fromOsgb36(double lat, double lon) {
        double[] en = NATIONAL_GRID.forward(lat, lon);
        if (en[0] < 0 || en[0] >= 700_000 || en[1] < 0 || en[1] >= 1_300_000) return null;
        return new BritishGrid(en[0], en[1]);
    }

    /** "TQ 30080 80660" (1 m resolution). */
    public String gridReference() {
        int e100k = (int) Math.floor(easting / 100_000);
        int n100k = (int) Math.floor(northing / 100_000);
        int l1 = (19 - n100k) - (19 - n100k) % 5 + (e100k + 10) / 5;
        int l2 = (19 - n100k) * 5 % 25 + e100k % 5;
        if (l1 > 7) l1++; // the grid skips the letter I
        if (l2 > 7) l2++;
        return String.format(Locale.ROOT, "%c%c %05d %05d", (char) ('A' + l1), (char) ('A' + l2),
                (long) Math.floor(easting % 100_000), (long) Math.floor(northing % 100_000));
    }

    /** "E 530268  N 179640". */
    public String numeric() {
        return String.format(Locale.ROOT, "E %d  N %d", (long) Math.floor(easting), (long) Math.floor(northing));
    }

    static double[] wgs84ToOsgb36(double lat, double lon) {
        double[] xyz = toCartesian(lat, lon, Utm.WGS84_A, Utm.WGS84_F);
        double x = xyz[0];
        double y = xyz[1];
        double z = xyz[2];
        double x2 = TX + (1 + S) * x - RZ * y + RY * z;
        double y2 = TY + RZ * x + (1 + S) * y - RX * z;
        double z2 = TZ - RY * x + RX * y + (1 + S) * z;
        return toGeodetic(x2, y2, z2, AIRY_A, (AIRY_A - AIRY_B) / AIRY_A);
    }

    private static double[] toCartesian(double latDeg, double lonDeg, double a, double f) {
        double e2 = f * (2 - f);
        double lat = Math.toRadians(latDeg);
        double lon = Math.toRadians(lonDeg);
        double n = a / Math.sqrt(1 - e2 * Math.sin(lat) * Math.sin(lat));
        return new double[]{n * Math.cos(lat) * Math.cos(lon), n * Math.cos(lat) * Math.sin(lon), n * (1 - e2) * Math.sin(lat)};
    }

    private static double[] toGeodetic(double x, double y, double z, double a, double f) {
        double e2 = f * (2 - f);
        double p = Math.sqrt(x * x + y * y);
        double lat = Math.atan2(z, p * (1 - e2));
        for (int i = 0; i < 10; i++) {
            double n = a / Math.sqrt(1 - e2 * Math.sin(lat) * Math.sin(lat));
            lat = Math.atan2(z + e2 * n * Math.sin(lat), p);
        }
        return new double[]{Math.toDegrees(lat), Math.toDegrees(Math.atan2(y, x))};
    }
}

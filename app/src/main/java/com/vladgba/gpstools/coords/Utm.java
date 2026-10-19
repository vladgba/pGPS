package com.vladgba.gpstools.coords;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** UTM on WGS84 (latitudes 80°S to 84°N), including the Norway and Svalbard zone exceptions. */
public final class Utm {

    static final double WGS84_A = 6378137.0;
    static final double WGS84_F = 1 / 298.257223563;
    private static final String BANDS = "CDEFGHJKLMNPQRSTUVWX";
    private static final Pattern TEXT = Pattern.compile(
            "^\\s*(\\d{1,2})\\s*([C-HJ-NP-Xc-hj-np-x])\\s+(\\d+(?:\\.\\d+)?)\\s*[mE]?\\s+(\\d+(?:\\.\\d+)?)\\s*[mN]?\\s*$");

    public final int zone;
    public final char band;
    public final double easting;
    public final double northing;

    private Utm(int zone, char band, double easting, double northing) {
        this.zone = zone;
        this.band = band;
        this.easting = easting;
        this.northing = northing;
    }

    public boolean isNorthern() {
        return band >= 'N';
    }

    /** @return null outside the UTM latitude range (polar areas use UPS) */
    public static Utm from(double lat, double lon) {
        if (lat < -80 || lat > 84) return null;
        lon = normalizeLon(lon);
        int zone = (int) Math.floor((lon + 180) / 6) + 1;
        if (zone > 60) zone = 60;
        // Norway: zone 32 widened over south-west Norway.
        if (lat >= 56 && lat < 64 && lon >= 3 && lon < 12) zone = 32;
        // Svalbard: zones 31/33/35/37 only.
        if (lat >= 72) {
            if (lon >= 0 && lon < 9) zone = 31;
            else if (lon >= 9 && lon < 21) zone = 33;
            else if (lon >= 21 && lon < 33) zone = 35;
            else if (lon >= 33 && lon < 42) zone = 37;
        }
        double[] en = projection(zone, lat >= 0).forward(lat, lon);
        return new Utm(zone, band(lat), en[0], en[1]);
    }

    static TransverseMercator projection(int zone, boolean northern) {
        return new TransverseMercator(WGS84_A, WGS84_F, 0.9996, 0, zone * 6 - 183, 500_000, northern ? 0 : 10_000_000);
    }

    static char band(double lat) {
        int index = (int) Math.floor((lat + 80) / 8);
        return BANDS.charAt(Math.max(0, Math.min(BANDS.length() - 1, index))); // X spans 72..84
    }

    public double[] toLatLon() {
        return projection(zone, isNorthern()).inverse(easting, northing);
    }

    /** "34T 690123 4723456" (metre resolution). */
    public String format() {
        return String.format(Locale.ROOT, "%d%c %d %d", zone, band, (long) Math.floor(easting), (long) Math.floor(northing));
    }

    /** Parses "34T 690123 4723456" (band letter required); null if it does not look like UTM. */
    public static double[] parse(String text) {
        Matcher m = TEXT.matcher(text);
        if (!m.matches()) return null;
        int zone = Integer.parseInt(m.group(1));
        if (zone < 1 || zone > 60) return null;
        char band = Character.toUpperCase(m.group(2).charAt(0));
        double e = Double.parseDouble(m.group(3));
        double n = Double.parseDouble(m.group(4));
        if (e < 100_000 || e > 900_000 || n < 0 || n > 10_000_000) return null;
        return new Utm(zone, band, e, n).toLatLon();
    }

    static double normalizeLon(double lon) {
        double r = (lon + 180) % 360;
        if (r < 0) r += 360;
        return r - 180;
    }
}

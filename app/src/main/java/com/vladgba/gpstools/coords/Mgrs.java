package com.vladgba.gpstools.coords;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Military Grid Reference System (WGS84, "AA" lettering scheme) built on {@link Utm}.
 * Polar areas (UPS) are not supported.
 */
public final class Mgrs {

    private static final String[] COLUMN_SETS = {"ABCDEFGH", "JKLMNPQR", "STUVWXYZ"};
    private static final String ROW_LETTERS = "ABCDEFGHJKLMNPQRSTUV";
    private static final Pattern TEXT = Pattern.compile(
            "^\\s*(\\d{1,2})\\s*([C-HJ-NP-X])\\s*([A-HJ-NP-Z])([A-HJ-NP-V])\\s*(\\d*)\\s*(\\d*)\\s*$");

    private Mgrs() {
    }

    /** e.g. "34T FN 90123 23456" at 1 m precision (digits = 5), null outside UTM. */
    public static String format(double lat, double lon, int digits) {
        Utm utm = Utm.from(lat, lon);
        if (utm == null) return null;
        String square = square(utm.zone, utm.easting, utm.northing);
        double div = Math.pow(10, 5 - digits);
        long e = (long) Math.floor((utm.easting % 100_000) / div);
        long n = (long) Math.floor((utm.northing % 100_000) / div);
        String fmt = "%0" + digits + "d";
        return String.format(Locale.ROOT, "%d%c %s " + fmt + " " + fmt, utm.zone, utm.band, square, e, n);
    }

    static String square(int zone, double easting, double northing) {
        int col = (int) Math.floor(easting / 100_000);          // 1..8
        int row = (int) Math.floor(northing / 100_000) % 20;
        char colLetter = COLUMN_SETS[(zone - 1) % 3].charAt(col - 1);
        char rowLetter = ROW_LETTERS.charAt((row + (zone % 2 == 0 ? 5 : 0)) % 20);
        return "" + colLetter + rowLetter;
    }

    /**
     * Parses "34TFN9012323456", "34T FN 901 234" and similar (any even number of digits up to
     * 10). Returns the centre of the referenced square, or null.
     */
    public static double[] parse(String text) {
        Matcher m = TEXT.matcher(text.toUpperCase(Locale.ROOT));
        if (!m.matches()) return null;
        int zone = Integer.parseInt(m.group(1));
        if (zone < 1 || zone > 60) return null;
        char band = m.group(2).charAt(0);
        char colLetter = m.group(3).charAt(0);
        char rowLetter = m.group(4).charAt(0);
        String digits = m.group(5) + m.group(6);
        if (digits.length() % 2 != 0 || digits.length() > 10) return null;
        int precision = digits.length() / 2;

        int col = COLUMN_SETS[(zone - 1) % 3].indexOf(colLetter);
        if (col < 0) return null;
        int row = ROW_LETTERS.indexOf(rowLetter) - (zone % 2 == 0 ? 5 : 0);
        row = ((row % 20) + 20) % 20;
        double cell = precision == 0 ? 100_000 : Math.pow(10, 5 - precision);
        double e = (col + 1) * 100_000.0 + (precision == 0 ? 0 : Long.parseLong(digits.substring(0, precision)) * cell) + cell / 2;
        double nInSquare = (precision == 0 ? 0 : Long.parseLong(digits.substring(precision)) * cell) + cell / 2;

        // The row letter repeats every 2000 km: pick the cycle that falls inside the band.
        boolean northern = band >= 'N';
        double bandLat = -80 + 8 * "CDEFGHJKLMNPQRSTUVWX".indexOf(band);
        TransverseMercator tm = Utm.projection(zone, northern);
        double bandNorthing = tm.forward(bandLat, zone * 6 - 183)[1];
        double n = row * 100_000.0 + nInSquare;
        while (n < bandNorthing - 100_000) n += 2_000_000;
        return tm.inverse(e, n);
    }
}

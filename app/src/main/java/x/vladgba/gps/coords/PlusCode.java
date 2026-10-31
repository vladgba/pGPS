package x.vladgba.gps.coords;

import java.util.Locale;

/**
 * Open Location Code ("Plus Codes"), implemented from the open specification
 * (github.com/google/open-location-code, Apache 2.0). Full codes only; short codes need a
 * reference locality.
 */
public final class PlusCode {

    private static final String ALPHABET = "23456789CFGHJMPQRVWX";
    private static final int SEPARATOR_POSITION = 8;
    private static final int PAIR_LENGTH = 10;
    private static final int GRID_LENGTH = 5;
    private static final int GRID_COLUMNS = 4;
    private static final int GRID_ROWS = 5;
    private static final long LAT_MULTIPLIER = 8000L * 3125;  // 20^3 · 5^5
    private static final long LNG_MULTIPLIER = 8000L * 1024;  // 20^3 · 4^5

    private PlusCode() {
    }

    /** @param length 10 (~14 m) or 11 (~3 m) digits */
    public static String encode(double lat, double lon, int length) {
        long latVal = (long) Math.floor(Math.round(lat * LAT_MULTIPLIER * 1e6) / 1e6) + 90 * LAT_MULTIPLIER;
        long lngVal = (long) Math.floor(Math.round(lon * LNG_MULTIPLIER * 1e6) / 1e6) + 180 * LNG_MULTIPLIER;
        latVal = Math.max(0, Math.min(180 * LAT_MULTIPLIER - 1, latVal));
        lngVal = Math.floorMod(lngVal, 360 * LNG_MULTIPLIER);

        StringBuilder reversed = new StringBuilder();
        for (int i = 0; i < GRID_LENGTH; i++) {
            int latDigit = (int) (latVal % GRID_ROWS);
            int lngDigit = (int) (lngVal % GRID_COLUMNS);
            reversed.append(ALPHABET.charAt(latDigit * GRID_COLUMNS + lngDigit));
            latVal /= GRID_ROWS;
            lngVal /= GRID_COLUMNS;
        }
        for (int i = 0; i < PAIR_LENGTH / 2; i++) {
            reversed.append(ALPHABET.charAt((int) (lngVal % 20)));
            reversed.append(ALPHABET.charAt((int) (latVal % 20)));
            latVal /= 20;
            lngVal /= 20;
            if (i == 0) reversed.append('+');
        }
        String code = reversed.reverse().toString();
        return code.substring(0, Math.min(code.length(), Math.max(PAIR_LENGTH, length) + 1));
    }

    /** Centre of a full code, or null if the text is not a valid full Plus Code. */
    public static double[] decode(String text) {
        String code = text.trim().toUpperCase(Locale.ROOT);
        int plus = code.indexOf('+');
        if (plus != SEPARATOR_POSITION || code.indexOf('+', plus + 1) >= 0) return null;
        String digits = code.substring(0, plus) + code.substring(plus + 1);
        int pad = digits.indexOf('0');
        if (pad >= 0) {
            if (pad < 2 || pad % 2 != 0) return null;
            for (int i = pad; i < digits.length(); i++) if (digits.charAt(i) != '0') return null;
            digits = digits.substring(0, pad);
        }
        if (digits.length() < 2) return null;
        for (int i = 0; i < digits.length(); i++) if (ALPHABET.indexOf(digits.charAt(i)) < 0) return null;
        // First digits are the latitude 0..8 (×20°) and longitude 0..17: reject out-of-range.
        if (ALPHABET.indexOf(digits.charAt(0)) > 8 || ALPHABET.indexOf(digits.charAt(1)) > 17) return null;

        double lat = -90;
        double lng = -180;
        double resolution = 20;
        int pairs = Math.min(digits.length(), PAIR_LENGTH);
        double latRes = 0;
        double lngRes = 0;
        for (int i = 0; i < pairs; i += 2) {
            lat += ALPHABET.indexOf(digits.charAt(i)) * resolution;
            if (i + 1 < pairs) lng += ALPHABET.indexOf(digits.charAt(i + 1)) * resolution;
            latRes = lngRes = resolution;
            resolution /= 20;
        }
        for (int i = PAIR_LENGTH; i < digits.length(); i++) {
            int index = ALPHABET.indexOf(digits.charAt(i));
            latRes /= GRID_ROWS;
            lngRes /= GRID_COLUMNS;
            lat += (index / GRID_COLUMNS) * latRes;
            lng += (index % GRID_COLUMNS) * lngRes;
        }
        return new double[]{lat + latRes / 2, lng + lngRes / 2};
    }
}

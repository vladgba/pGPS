package x.vladgba.gps.coords;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads a position typed in any supported format: decimal degrees, UTM, MGRS, a Plus Code, a
 * voice code, a text code or a geo: URI.
 */
public final class CoordinateParser {

    private static final Pattern DECIMAL = Pattern.compile(
            "^\\s*([-+]?\\d{1,2}(?:[.,]\\d+)?)\\s*°?\\s*([NSns])?\\s*[,;\\s]\\s*([-+]?\\d{1,3}(?:[.,]\\d+)?)\\s*°?\\s*([EWew])?\\s*$");
    private static final Pattern GEO_URI = Pattern.compile(
            "^\\s*geo:([-+]?\\d+(?:\\.\\d+)?),([-+]?\\d+(?:\\.\\d+)?).*$", Pattern.CASE_INSENSITIVE);

    private CoordinateParser() {
    }

    /** Like {@link #parse(String, double, double)} without a reference position. */
    public static double[] parse(String text) {
        return parse(text, Double.NaN, Double.NaN);
    }

    /**
     * @param refLat the reader's own position, needed for regional/local voice codes (NaN if
     *               unknown)
     * @return {latitude, longitude} or null when the text is not recognised
     */
    public static double[] parse(String text, double refLat, double refLon) {
        if (text == null || text.trim().isEmpty()) return null;
        double[] result = decimal(text);
        if (result == null) result = geoUri(text);
        if (result == null) result = PlusCode.decode(text);
        if (result == null) result = Mgrs.parse(text);
        if (result == null) result = Utm.parse(text);
        if (result == null && VoiceCode.looksLike(text)) {
            VoiceCode.Decoded d = VoiceCode.decode(text, refLat, refLon);
            if (d.ok()) result = new double[]{d.latitude, d.longitude};
        }
        // Text codes last: only accepted when their checksum passes, so ordinary words and
        // MGRS-like strings are not mistaken for them.
        if (result == null && TextCode.looksLike(text)) {
            TextCode.Decoded d = TextCode.decode(text, refLat, refLon);
            if (d.ok()) result = new double[]{d.latitude, d.longitude};
        }
        if (result == null || Math.abs(result[0]) > 90 || Math.abs(result[1]) > 180) return null;
        return result;
    }

    private static double[] decimal(String text) {
        // Accept "42.69, 23.33" as well as "42,69 23,33"; a comma decimal separator is only
        // unambiguous when a space separates the two numbers.
        Matcher m = DECIMAL.matcher(text);
        if (!m.matches()) return null;
        double lat = Double.parseDouble(m.group(1).replace(',', '.'));
        double lon = Double.parseDouble(m.group(3).replace(',', '.'));
        if (m.group(2) != null && Character.toUpperCase(m.group(2).charAt(0)) == 'S') lat = -Math.abs(lat);
        if (m.group(4) != null && Character.toUpperCase(m.group(4).charAt(0)) == 'W') lon = -Math.abs(lon);
        return new double[]{lat, lon};
    }

    private static double[] geoUri(String text) {
        Matcher m = GEO_URI.matcher(text);
        if (!m.matches()) return null;
        double lat = Double.parseDouble(m.group(1));
        double lon = Double.parseDouble(m.group(2));
        return lat == 0 && lon == 0 ? null : new double[]{lat, lon}; // geo:0,0?q=address is a search
    }
}

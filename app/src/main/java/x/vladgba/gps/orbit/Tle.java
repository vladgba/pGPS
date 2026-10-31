package x.vladgba.gps.orbit;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/** One NORAD two-line element set (mean elements, epoch in UTC). */
public final class Tle {

    public final String name;
    public final int noradId;
    public final long epochMs;
    public final double inclinationDeg;
    public final double raanDeg;
    public final double eccentricity;
    public final double argPerigeeDeg;
    public final double meanAnomalyDeg;
    /** Revolutions per day. */
    public final double meanMotion;

    private Tle(String name, int noradId, long epochMs, double inclinationDeg, double raanDeg,
                double eccentricity, double argPerigeeDeg, double meanAnomalyDeg, double meanMotion) {
        this.name = name;
        this.noradId = noradId;
        this.epochMs = epochMs;
        this.inclinationDeg = inclinationDeg;
        this.raanDeg = raanDeg;
        this.eccentricity = eccentricity;
        this.argPerigeeDeg = argPerigeeDeg;
        this.meanAnomalyDeg = meanAnomalyDeg;
        this.meanMotion = meanMotion;
    }

    /** Parses a 3-line (name + 2 lines) TLE file; malformed entries are skipped. */
    public static List<Tle> parseAll(String text) {
        List<Tle> result = new ArrayList<>();
        String[] lines = text.split("\\r?\\n");
        for (int i = 0; i + 2 < lines.length; ) {
            String l0 = lines[i];
            String l1 = lines[i + 1];
            String l2 = lines[i + 2];
            if (l1.startsWith("1 ") && l2.startsWith("2 ")) {
                Tle tle = parse(l0.trim(), l1, l2);
                if (tle != null) result.add(tle);
                i += 3;
            } else {
                i++;
            }
        }
        return result;
    }

    static Tle parse(String name, String line1, String line2) {
        try {
            int norad = Integer.parseInt(line1.substring(2, 7).trim());
            int yy = Integer.parseInt(line1.substring(18, 20).trim());
            double day = Double.parseDouble(line1.substring(20, 32).trim());
            double incl = Double.parseDouble(line2.substring(8, 16).trim());
            double raan = Double.parseDouble(line2.substring(17, 25).trim());
            double ecc = Double.parseDouble("0." + line2.substring(26, 33).trim());
            double argp = Double.parseDouble(line2.substring(34, 42).trim());
            double ma = Double.parseDouble(line2.substring(43, 51).trim());
            double n = Double.parseDouble(line2.substring(52, 63).trim());
            if (n <= 0) return null;
            int year = yy < 57 ? 2000 + yy : 1900 + yy;
            Calendar c = Calendar.getInstance(TimeZone.getTimeZone("UTC"), Locale.ROOT);
            c.clear();
            c.set(year, Calendar.JANUARY, 1, 0, 0, 0);
            long epoch = c.getTimeInMillis() + Math.round((day - 1) * 86_400_000d);
            return new Tle(name, norad, epoch, incl, raan, ecc, argp, ma, n);
        } catch (RuntimeException e) {
            return null;
        }
    }
}

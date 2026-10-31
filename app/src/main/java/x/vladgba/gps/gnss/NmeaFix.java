package x.vladgba.gps.gnss;

/**
 * Extracts what the Location API does not expose from NMEA: DOP values, fix dimension,
 * GGA fix quality, MSL altitude and geoid separation. Only GGA and GSA are parsed; sentences
 * with a bad checksum are ignored.
 */
public final class NmeaFix {

    public static final int FIX_TYPE_UNKNOWN = 0;
    public static final int FIX_TYPE_NONE = 1;
    public static final int FIX_TYPE_2D = 2;
    public static final int FIX_TYPE_3D = 3;

    private static final long STALE_AFTER_MS = 5_000;

    private int fixType = FIX_TYPE_UNKNOWN;
    private int ggaQuality = -1;
    private int ggaSatellites = -1;
    private double pdop = Double.NaN;
    private double hdop = Double.NaN;
    private double vdop = Double.NaN;
    private double mslAltitude = Double.NaN;
    private double geoidSeparation = Double.NaN;
    private long updatedAtElapsedMs = Long.MIN_VALUE;

    /**
     * @param sentence     raw sentence, e.g. "$GNGSA,A,3,...*2E"
     * @param nowElapsedMs current {@code SystemClock.elapsedRealtime()}
     * @return true when the sentence changed the parsed state
     */
    public boolean parse(String sentence, long nowElapsedMs) {
        String body = validatedBody(sentence);
        if (body == null) return false;
        String[] f = body.split(",", -1);
        if (f.length == 0 || f[0].length() < 5) return false;
        String type = f[0].substring(f[0].length() - 3);
        switch (type) {
            case "GGA":
                return parseGga(f, nowElapsedMs);
            case "GSA":
                return parseGsa(f, nowElapsedMs);
            default:
                return false;
        }
    }

    private boolean parseGga(String[] f, long now) {
        if (f.length < 12) return false;
        ggaQuality = parseInt(f[6], -1);
        ggaSatellites = parseInt(f[7], -1);
        if (ggaQuality > 0) {
            hdop = dop(f[8]);
            mslAltitude = parseDouble(f[9]);
            geoidSeparation = parseDouble(f[11]);
        } else {
            mslAltitude = Double.NaN;
            geoidSeparation = Double.NaN;
        }
        updatedAtElapsedMs = now;
        return true;
    }

    private boolean parseGsa(String[] f, long now) {
        // $xxGSA,mode,fixType,sv1..sv12,PDOP,HDOP,VDOP[,systemId]
        if (f.length < 18) return false;
        fixType = parseInt(f[2], FIX_TYPE_UNKNOWN);
        pdop = dop(f[15]);
        hdop = dop(f[16]);
        vdop = dop(f[17]);
        updatedAtElapsedMs = now;
        return true;
    }

    /** Returns the part between '$' and '*' if the checksum matches, else null. */
    static String validatedBody(String sentence) {
        if (sentence == null) return null;
        String s = sentence.trim();
        if (s.length() < 6 || (s.charAt(0) != '$' && s.charAt(0) != '!')) return null;
        int star = s.lastIndexOf('*');
        if (star < 0) return s.substring(1); // checksum is optional in NMEA 0183
        if (star + 3 > s.length()) return null;
        int expected;
        try {
            expected = Integer.parseInt(s.substring(star + 1, star + 3), 16);
        } catch (NumberFormatException e) {
            return null;
        }
        int sum = 0;
        for (int i = 1; i < star; i++) sum ^= s.charAt(i);
        return sum == expected ? s.substring(1, star) : null;
    }

    /** DOP value; receivers send 99.9/99.99 as a "no fix" placeholder, treated as unknown. */
    private static double dop(String v) {
        double d = parseDouble(v);
        return d >= 50 ? Double.NaN : d;
    }

    private static double parseDouble(String v) {
        if (v == null || v.isEmpty()) return Double.NaN;
        try {
            return Double.parseDouble(v);
        } catch (NumberFormatException e) {
            return Double.NaN;
        }
    }

    private static int parseInt(String v, int fallback) {
        if (v == null || v.isEmpty()) return fallback;
        try {
            return Integer.parseInt(v);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    public boolean isFresh(long nowElapsedMs) {
        return updatedAtElapsedMs != Long.MIN_VALUE && nowElapsedMs - updatedAtElapsedMs <= STALE_AFTER_MS;
    }

    public void reset() {
        fixType = FIX_TYPE_UNKNOWN;
        ggaQuality = -1;
        ggaSatellites = -1;
        pdop = hdop = vdop = Double.NaN;
        mslAltitude = geoidSeparation = Double.NaN;
        updatedAtElapsedMs = Long.MIN_VALUE;
    }

    public int getFixType() { return fixType; }
    public int getGgaQuality() { return ggaQuality; }
    public int getGgaSatellites() { return ggaSatellites; }
    public double getPdop() { return pdop; }
    public double getHdop() { return hdop; }
    public double getVdop() { return vdop; }
    public double getMslAltitude() { return mslAltitude; }
    public double getGeoidSeparation() { return geoidSeparation; }

    /** NMEA GGA fix quality indicator as text. */
    public static String qualityName(int quality) {
        switch (quality) {
            case 0: return "Invalid";
            case 1: return "GNSS fix";
            case 2: return "DGNSS";
            case 3: return "PPS";
            case 4: return "RTK fixed";
            case 5: return "RTK float";
            case 6: return "Dead reckoning";
            case 7: return "Manual";
            case 8: return "Simulation";
            default: return null;
        }
    }
}

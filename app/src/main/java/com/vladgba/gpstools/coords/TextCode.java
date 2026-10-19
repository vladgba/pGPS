package com.vladgba.gpstools.coords;

import java.util.Locale;

/**
 * Text code (format version 1): a position as the shortest robust letters-and-digits string,
 * for SMS and chat. No separators; the length alone tells the precision and the range.
 *
 * <p><b>Alphabet.</b> Crockford Base32 "0123456789ABCDEFGHJKMNPQRSTVWXYZ", 5 bits per
 * character. Reading is case-insensitive and forgiving: O is read as 0, I and L as 1, and any
 * non-alphanumeric character (space, hyphen) is ignored.
 *
 * <p><b>Grids.</b> Precise: latitude 1/40000° (2.8 m), longitude 1/32768° (3.4 m at the
 * equator); standard: exactly 4 × 4 precise cells, latitude 1/10000° (11 m), longitude 1/8192°
 * (13.6 m at the equator, 10 m at 43°). Latitude/longitude bits are interleaved.
 *
 * <p><b>Lengths</b> ("local"/"regional" codes are completed with the reader's own position, like
 * Plus Code short codes; their windows are identical for both precisions):
 * <pre>
 *            standard (~10 m)   precise (~3 m)   reader must be within
 *  local     6 chars (CRC-6)    7 chars (CRC-7)  ±0.205° lat (±22.8 km), ±0.25° lon
 *  regional  8 chars (CRC-8)    9 chars (CRC-9)  ±3.28° lat (±364 km), ±4° lon
 *  global   10 chars (CRC-7)   11 chars (CRC-8)  anywhere
 * </pre>
 * Every CRC is at least 6 bits wide, so any single wrong character (≤ 5-bit burst) is always
 * detected. All constants are part of the format.
 */
public final class TextCode {

    public enum Precision { STANDARD, PRECISE }

    public enum Range { LOCAL, REGIONAL, GLOBAL }

    public enum Tier {
        LOCAL_STANDARD(Range.LOCAL, Precision.STANDARD, 6, 12, 6, 0x15),
        LOCAL_PRECISE(Range.LOCAL, Precision.PRECISE, 7, 14, 7, 0x2B),
        REGIONAL_STANDARD(Range.REGIONAL, Precision.STANDARD, 8, 16, 8, 0x5C),
        REGIONAL_PRECISE(Range.REGIONAL, Precision.PRECISE, 9, 18, 9, 0x0B7),
        GLOBAL_STANDARD(Range.GLOBAL, Precision.STANDARD, 10, 0, 7, 0x4D),
        GLOBAL_PRECISE(Range.GLOBAL, Precision.PRECISE, 11, 0, 8, 0xC3);

        public final Range range;
        public final Precision precision;
        public final int chars;
        final int axisBits;
        final int crcBits;
        final int crcInit;

        Tier(Range range, Precision precision, int chars, int axisBits, int crcBits, int crcInit) {
            this.range = range;
            this.precision = precision;
            this.chars = chars;
            this.axisBits = axisBits;
            this.crcBits = crcBits;
            this.crcInit = crcInit;
        }

        public static Tier of(Range range, Precision precision) {
            for (Tier t : values()) if (t.range == range && t.precision == precision) return t;
            throw new IllegalArgumentException();
        }

        long latPerDegree() {
            return precision == Precision.PRECISE ? 40_000 : 10_000;
        }

        long lngPerDegree() {
            return precision == Precision.PRECISE ? 32_768 : 8_192;
        }

        /** Half the north-south window in metres (infinite for global codes). */
        public double rangeMeters() {
            return axisBits == 0 ? Double.POSITIVE_INFINITY : (1L << axisBits) / 2.0 / latPerDegree() * 111_320;
        }

        /** Half the east-west window in metres at a latitude. */
        public double rangeMetersEastWest(double latDeg) {
            return axisBits == 0 ? Double.POSITIVE_INFINITY
                    : (1L << axisBits) / 2.0 / lngPerDegree() * 111_320 * Math.cos(Math.toRadians(latDeg));
        }

        /** Cell size in metres north-south. */
        public double cellMeters() {
            return 111_320.0 / latPerDegree();
        }
    }

    public static final class Decoded {
        public final Tier tier;
        public final double latitude;
        public final double longitude;
        /** null when decoding succeeded. */
        public final String error;

        Decoded(Tier tier, double latitude, double longitude, String error) {
            this.tier = tier;
            this.latitude = latitude;
            this.longitude = longitude;
            this.error = error;
        }

        public boolean ok() {
            return error == null;
        }
    }

    public static final String ERROR_CHARACTERS = "characters";
    public static final String ERROR_LENGTH = "length";
    public static final String ERROR_CHECKSUM = "checksum";
    public static final String ERROR_REFERENCE = "reference";
    public static final String ERROR_RANGE = "range";

    private static final String ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ";

    private TextCode() {
    }

    // ---- Encoding -----------------------------------------------------------------------------

    public static String encode(double lat, double lon, Tier tier) {
        long latCells = 180 * tier.latPerDegree();
        long lngCells = 360 * tier.lngPerDegree();
        long latIndex = index(lat + 90, tier.latPerDegree(), latCells, false);
        long lngIndex = index(lon + 180, tier.lngPerDegree(), lngCells, true);
        long payload;
        int payloadBits;
        if (tier.range == Range.GLOBAL) {
            int latBits = bitsFor(latCells);
            int lngBits = bitsFor(lngCells);
            payload = VoiceCode.interleave(latIndex, latBits, lngIndex, lngBits);
            payloadBits = latBits + lngBits;
        } else {
            long mask = (1L << tier.axisBits) - 1;
            payload = VoiceCode.interleave(latIndex & mask, tier.axisBits, lngIndex & mask, tier.axisBits);
            payloadBits = 2 * tier.axisBits;
        }
        long bits = (payload << tier.crcBits) | crc(payload, payloadBits, tier);
        char[] out = new char[tier.chars];
        for (int i = tier.chars - 1; i >= 0; i--) {
            out[i] = ALPHABET.charAt((int) (bits & 31));
            bits >>>= 5;
        }
        return new String(out);
    }

    // ---- Decoding -----------------------------------------------------------------------------

    /** @param refLat reader's latitude for local/regional codes, NaN when unknown */
    public static Decoded decode(String text, double refLat, double refLon) {
        String clean = normalize(text);
        if (clean == null) return new Decoded(null, Double.NaN, Double.NaN, ERROR_CHARACTERS);
        Tier tier = null;
        for (Tier t : Tier.values()) if (t.chars == clean.length()) tier = t;
        if (tier == null) return new Decoded(null, Double.NaN, Double.NaN, ERROR_LENGTH);

        long bits = 0;
        for (int i = 0; i < clean.length(); i++) bits = (bits << 5) | ALPHABET.indexOf(clean.charAt(i));
        int payloadBits = tier.chars * 5 - tier.crcBits;
        long payload = bits >>> tier.crcBits;
        if (crc(payload, payloadBits, tier) != (bits & ((1L << tier.crcBits) - 1))) {
            return new Decoded(tier, Double.NaN, Double.NaN, ERROR_CHECKSUM);
        }
        long latCells = 180 * tier.latPerDegree();
        long lngCells = 360 * tier.lngPerDegree();
        long latIndex;
        long lngIndex;
        if (tier.range == Range.GLOBAL) {
            long[] d = VoiceCode.deinterleave(payload, bitsFor(latCells), bitsFor(lngCells));
            latIndex = d[0];
            lngIndex = d[1];
            if (latIndex >= latCells || lngIndex >= lngCells) return new Decoded(tier, Double.NaN, Double.NaN, ERROR_RANGE);
        } else {
            if (Double.isNaN(refLat) || Double.isNaN(refLon)) return new Decoded(tier, Double.NaN, Double.NaN, ERROR_REFERENCE);
            long[] d = VoiceCode.deinterleave(payload, tier.axisBits, tier.axisBits);
            long window = 1L << tier.axisBits;
            latIndex = VoiceCode.nearest(d[0], index(refLat + 90, tier.latPerDegree(), latCells, false), window, -1);
            lngIndex = VoiceCode.nearest(d[1], index(refLon + 180, tier.lngPerDegree(), lngCells, true), window, lngCells);
            if (latIndex < 0 || latIndex >= latCells) return new Decoded(tier, Double.NaN, Double.NaN, ERROR_RANGE);
        }
        return new Decoded(tier, (latIndex + 0.5) / tier.latPerDegree() - 90,
                (lngIndex + 0.5) / tier.lngPerDegree() - 180, null);
    }

    /**
     * Share of the window between reader and result on the worse axis (0..1); near 1 the code may
     * belong to a place beyond the window. 0 for global codes.
     */
    public static double windowFraction(Decoded d, double refLat, double refLon) {
        if (d.tier == null || d.tier.range == Range.GLOBAL || !d.ok()) return 0;
        double halfLat = (1L << d.tier.axisBits) / 2.0 / d.tier.latPerDegree();
        double halfLon = (1L << d.tier.axisBits) / 2.0 / d.tier.lngPerDegree();
        double dLon = Math.abs(d.longitude - refLon);
        if (dLon > 180) dLon = 360 - dLon;
        return Math.max(Math.abs(d.latitude - refLat) / halfLat, dLon / halfLon);
    }

    /** True for a plausible text code: 6..11 code characters, at least one digit and one letter. */
    public static boolean looksLike(String text) {
        String clean = normalize(text);
        if (clean == null || clean.length() < 6 || clean.length() > 11) return false;
        boolean digit = false;
        boolean letter = false;
        for (int i = 0; i < clean.length(); i++) {
            if (Character.isDigit(clean.charAt(i))) digit = true;
            else letter = true;
        }
        return digit && letter;
    }

    // ---- Helpers ------------------------------------------------------------------------------

    /** Uppercase code characters only, with the usual look-alike fixes; null on a foreign letter. */
    static String normalize(String text) {
        if (text == null) return null;
        StringBuilder sb = new StringBuilder();
        for (char ch : text.trim().toUpperCase(Locale.ROOT).toCharArray()) {
            if (ch == 'O') ch = '0';
            else if (ch == 'I' || ch == 'L') ch = '1';
            if (ALPHABET.indexOf(ch) >= 0) sb.append(ch);
            else if (Character.isLetterOrDigit(ch)) return null; // U, or a non-Latin letter
        }
        return sb.toString();
    }

    private static long index(double degreesFromOrigin, long perDegree, long cells, boolean wrap) {
        long i = (long) Math.floor(Math.round(degreesFromOrigin * perDegree * 1e6) / 1e6);
        return wrap ? Math.floorMod(i, cells) : Math.max(0, Math.min(cells - 1, i));
    }

    private static int bitsFor(long cells) {
        return 64 - Long.numberOfLeadingZeros(cells - 1);
    }

    /**
     * Bitwise CRC, MSB first, polynomials with the x^0 term (so all bursts up to the width are
     * detected): 6: x^6+x+1, 7: x^7+x^3+1, 8: x^8+x^2+x+1, 9: x^9+x^4+1.
     */
    static long crc(long payload, int bits, Tier tier) {
        int width = tier.crcBits;
        long poly;
        switch (width) {
            case 6: poly = 0x03; break;
            case 7: poly = 0x09; break;
            case 8: poly = 0x07; break;
            default: poly = 0x11; break;
        }
        long top = 1L << (width - 1);
        long mask = (1L << width) - 1;
        long crc = tier.crcInit & mask;
        for (int i = bits - 1; i >= 0; i--) {
            boolean msb = (crc & top) != 0;
            crc = (crc << 1) & mask;
            if (msb ^ (((payload >>> i) & 1) == 1)) crc ^= poly;
        }
        return crc;
    }
}

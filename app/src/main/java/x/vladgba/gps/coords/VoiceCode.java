package x.vladgba.gps.coords;

import java.util.Locale;

/**
 * Voice code (format version 1): a position as one pronounceable word made of consonant-vowel
 * syllables, built to be read aloud over a phone or radio, typed into a message or put in a QR.
 *
 * <p><b>Syllables.</b> 16 consonants (b d f g h j k l m n p r s t v z) × 4 vowels (a e i o) = 64
 * syllables, 6 bits each. Written as one word ("bepiganotuhodevaki"); hyphens, spaces and case
 * are ignored when reading.
 *
 * <p><b>Grid.</b> ~3 m cells: latitude steps of 1/40000° (2.8 m) and longitude steps of 1/32768°
 * (3.4 m at the equator). Latitude and longitude bits are interleaved, most significant first,
 * so codes that share a beginning are near each other.
 *
 * <p><b>Lengths</b> (the decoder recognises the tier from the syllable count):
 * <ul>
 *   <li>Global, 9 syllables (18 letters): whole cell (47 bits) + CRC-7.</li>
 *   <li>Regional, 7 syllables (14 letters): low 17 bits of each index + CRC-8; the receiver must
 *       be within ±1.64° of latitude (182 km) and ±2.0° of longitude (223 km × cos latitude).</li>
 *   <li>Local, 5 syllables (10 letters): low 12 bits of each index + CRC-6; receiver within
 *       ±0.051° of latitude (5.7 km) and ±0.0625° of longitude (7.0 km × cos latitude).</li>
 * </ul>
 * Regional and local codes are resolved like Plus Code short codes: the candidate cell nearest to
 * the receiver's own position is chosen.
 *
 * <p><b>Checks.</b> Every single-syllable error (a burst of at most 6 bits) is always detected,
 * since every CRC is at least 6 bits wide; each tier seeds its CRC differently, so a truncated
 * code does not pass as a shorter tier. All constants are part of the format: changing them
 * changes every code.
 */
public final class VoiceCode {

    public enum Tier {
        GLOBAL(9, 0, 7, 0x5A),
        REGIONAL(7, 17, 8, 0xA5),
        LOCAL(5, 12, 6, 0x2D);

        public final int syllables;
        /** Low bits kept per axis (0 = whole indices). */
        final int axisBits;
        final int crcBits;
        final int crcInit;

        Tier(int syllables, int axisBits, int crcBits, int crcInit) {
            this.syllables = syllables;
            this.axisBits = axisBits;
            this.crcBits = crcBits;
            this.crcInit = crcInit;
        }

        /** Half the north-south extent the receiver may be away from the target, in metres. */
        public double rangeMeters() {
            return axisBits == 0 ? Double.POSITIVE_INFINITY : (1L << axisBits) / 2.0 / LAT_PER_DEGREE * 111_320;
        }

        /** Half the east-west extent in metres at the given latitude. */
        public double rangeMetersEastWest(double latDeg) {
            return axisBits == 0 ? Double.POSITIVE_INFINITY
                    : (1L << axisBits) / 2.0 / LNG_PER_DEGREE * 111_320 * Math.cos(Math.toRadians(latDeg));
        }

        /** Half the east-west extent in degrees of longitude. */
        double rangeLongitudeDegrees() {
            return axisBits == 0 ? 180 : (1L << axisBits) / 2.0 / LNG_PER_DEGREE;
        }
    }

    /** Result of {@link #decode}. Exactly one of {@code error} or the coordinates is meaningful. */
    public static final class Decoded {
        public final Tier tier;
        public final double latitude;
        public final double longitude;
        public final Error error;

        Decoded(Tier tier, double latitude, double longitude, Error error) {
            this.tier = tier;
            this.latitude = latitude;
            this.longitude = longitude;
            this.error = error;
        }

        public boolean ok() {
            return error == null;
        }
    }

    public enum Error {
        /** Contains letters that are not one of the 32 syllables. */
        NOT_SYLLABLES,
        /** Not 6, 8 or 11 syllables. */
        WRONG_LENGTH,
        /** The check failed: at least one syllable is wrong. */
        CHECKSUM,
        /** A regional or local code needs the receiver's own position. */
        NEEDS_REFERENCE,
        /** Decodes to a cell outside the globe (corrupted code). */
        OUT_OF_RANGE
    }

    private static final String CONSONANTS = "bdfghjklmnprstvz";
    private static final String VOWELS = "aeio";
    private static final int SYLLABLE_BITS = 6;

    private static final long LAT_PER_DEGREE = 40_000;
    /**
     * 2^15 per degree: the cells around a parallel (180 · 2^16) are then a multiple of every
     * window, which keeps short codes unambiguous across the ±180° meridian.
     */
    private static final long LNG_PER_DEGREE = 32_768;
    private static final long LAT_CELLS = 180 * LAT_PER_DEGREE;   // < 2^23
    private static final long LNG_CELLS = 360 * LNG_PER_DEGREE;   // < 2^24
    private static final int LAT_BITS = 23;
    private static final int LNG_BITS = 24;

    private VoiceCode() {
    }

    // ---- Encoding -----------------------------------------------------------------------------

    public static String encode(double lat, double lon, Tier tier) {
        long latIndex = latIndex(lat);
        long lngIndex = lngIndex(lon);
        long payload;
        int payloadBits;
        if (tier == Tier.GLOBAL) {
            payload = interleave(latIndex, LAT_BITS, lngIndex, LNG_BITS);
            payloadBits = LAT_BITS + LNG_BITS;
        } else {
            long mask = (1L << tier.axisBits) - 1;
            payload = interleave(latIndex & mask, tier.axisBits, lngIndex & mask, tier.axisBits);
            payloadBits = 2 * tier.axisBits;
        }
        long bits = (payload << tier.crcBits) | crc(payload, payloadBits, tier);
        return toSyllables(bits, tier.syllables);
    }

    /** "ka-li-mo-de-fa-be" style reading aid, syllables separated by hyphens. */
    public static String spoken(String code) {
        String clean = normalize(code);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i + 1 < clean.length(); i += 2) {
            if (sb.length() > 0) sb.append('-');
            sb.append(clean, i, i + 2);
        }
        return sb.toString();
    }

    // ---- Decoding -----------------------------------------------------------------------------

    /**
     * @param refLat receiver latitude, NaN when unknown (only global codes then decode)
     * @param refLon receiver longitude
     */
    public static Decoded decode(String text, double refLat, double refLon) {
        String clean = normalize(text);
        if (clean.isEmpty() || clean.length() % 2 != 0) {
            return new Decoded(null, Double.NaN, Double.NaN, clean.isEmpty() ? Error.NOT_SYLLABLES : Error.WRONG_LENGTH);
        }
        long bits = 0;
        for (int i = 0; i < clean.length(); i += 2) {
            int c = CONSONANTS.indexOf(clean.charAt(i));
            int v = VOWELS.indexOf(clean.charAt(i + 1));
            if (c < 0 || v < 0) return new Decoded(null, Double.NaN, Double.NaN, Error.NOT_SYLLABLES);
            bits = (bits << SYLLABLE_BITS) | ((long) c << 2) | v;
        }
        Tier tier = null;
        for (Tier t : Tier.values()) if (t.syllables * 2 == clean.length()) tier = t;
        if (tier == null) return new Decoded(null, Double.NaN, Double.NaN, Error.WRONG_LENGTH);

        int totalBits = tier.syllables * SYLLABLE_BITS;
        int payloadBits = totalBits - tier.crcBits;
        long payload = bits >>> tier.crcBits;
        long check = bits & ((1L << tier.crcBits) - 1);
        if (crc(payload, payloadBits, tier) != check) return new Decoded(tier, Double.NaN, Double.NaN, Error.CHECKSUM);

        long latIndex;
        long lngIndex;
        if (tier == Tier.GLOBAL) {
            long[] d = deinterleave(payload, LAT_BITS, LNG_BITS);
            latIndex = d[0];
            lngIndex = d[1];
            if (latIndex >= LAT_CELLS || lngIndex >= LNG_CELLS) {
                return new Decoded(tier, Double.NaN, Double.NaN, Error.OUT_OF_RANGE);
            }
        } else {
            if (Double.isNaN(refLat) || Double.isNaN(refLon)) {
                return new Decoded(tier, Double.NaN, Double.NaN, Error.NEEDS_REFERENCE);
            }
            long[] d = deinterleave(payload, tier.axisBits, tier.axisBits);
            long window = 1L << tier.axisBits;
            latIndex = nearest(d[0], latIndex(refLat), window, -1);
            lngIndex = nearest(d[1], lngIndex(refLon), window, LNG_CELLS);
            if (latIndex < 0 || latIndex >= LAT_CELLS) return new Decoded(tier, Double.NaN, Double.NaN, Error.OUT_OF_RANGE);
        }
        double lat = (latIndex + 0.5) / LAT_PER_DEGREE - 90;
        double lon = (lngIndex + 0.5) / LNG_PER_DEGREE - 180;
        return new Decoded(tier, lat, lon, null);
    }

    /**
     * How far the decoded short-code position is from the receiver, as a fraction of the
     * tier's half-window on the worse axis (0 = at the receiver, 1 = at the edge). Results near 1
     * may belong to a place beyond the window. Global codes return 0.
     */
    public static double windowFraction(Decoded d, double refLat, double refLon) {
        if (d.tier == null || d.tier == Tier.GLOBAL || !d.ok()) return 0;
        double halfLat = (1L << d.tier.axisBits) / 2.0 / LAT_PER_DEGREE;
        double halfLon = (1L << d.tier.axisBits) / 2.0 / LNG_PER_DEGREE;
        double dLon = Math.abs(d.longitude - refLon);
        if (dLon > 180) dLon = 360 - dLon;
        return Math.max(Math.abs(d.latitude - refLat) / halfLat, dLon / halfLon);
    }

    /** True when the text looks like a voice code (only valid syllables, plausible length). */
    public static boolean looksLike(String text) {
        String clean = normalize(text);
        if (clean.length() < 10 || clean.length() % 2 != 0) return false;
        for (int i = 0; i < clean.length(); i += 2) {
            if (CONSONANTS.indexOf(clean.charAt(i)) < 0 || VOWELS.indexOf(clean.charAt(i + 1)) < 0) return false;
        }
        return true;
    }

    // ---- Helpers ------------------------------------------------------------------------------

    static String normalize(String text) {
        if (text == null) return "";
        String lower = text.trim().toLowerCase(Locale.ROOT);
        StringBuilder sb = new StringBuilder(lower.length());
        for (int i = 0; i < lower.length(); i++) {
            char ch = lower.charAt(i);
            if (ch >= 'a' && ch <= 'z') sb.append(ch);
            else if (ch != '-' && ch != ' ' && ch != '.' && ch != '·' && ch != '_') return "#"; // foreign symbol
        }
        return sb.toString();
    }

    private static long latIndex(double lat) {
        long i = (long) Math.floor(Math.round((lat + 90) * LAT_PER_DEGREE * 1e6) / 1e6);
        return Math.max(0, Math.min(LAT_CELLS - 1, i));
    }

    private static long lngIndex(double lon) {
        long i = (long) Math.floor(Math.round((lon + 180) * LNG_PER_DEGREE * 1e6) / 1e6);
        return Math.floorMod(i, LNG_CELLS);
    }

    /**
     * Valid index whose residue modulo {@code window} is {@code low}, closest to {@code ref}.
     * With {@code wrap} > 0 the axis is circular (longitude) and indices live in [0, wrap); the
     * cell count is not a multiple of the window, so candidates are searched on both sides of
     * the wrap instead of reducing the residue arithmetic modulo {@code wrap}.
     */
    static long nearest(long low, long ref, long window, long wrap) {
        long best = -1;
        long bestDistance = Long.MAX_VALUE;
        long[] shifts = wrap > 0 ? new long[]{-wrap, 0, wrap} : new long[]{0};
        for (long shift : shifts) {
            long r = ref + shift;
            long base = r - Math.floorMod(r, window) + low;
            for (long c = base - window; c <= base + window; c += window) {
                if (c < 0 || (wrap > 0 && c >= wrap)) continue;
                long d = distance(c, ref, wrap);
                if (d < bestDistance) {
                    bestDistance = d;
                    best = c;
                }
            }
        }
        return best;
    }

    private static long distance(long a, long b, long wrap) {
        long d = Math.abs(a - b);
        return wrap > 0 ? Math.min(d % wrap, wrap - d % wrap) : d;
    }

    /** Interleaves bits, most significant first; the longer axis contributes its extra top bits first. */
    static long interleave(long a, int aBits, long b, int bBits) {
        long out = 0;
        for (int i = Math.max(aBits, bBits) - 1; i >= 0; i--) {
            if (i < aBits) out = (out << 1) | ((a >>> i) & 1);
            if (i < bBits) out = (out << 1) | ((b >>> i) & 1);
        }
        return out;
    }

    static long[] deinterleave(long v, int aBits, int bBits) {
        long a = 0;
        long b = 0;
        int pos = aBits + bBits - 1;
        for (int i = Math.max(aBits, bBits) - 1; i >= 0; i--) {
            if (i < aBits) a |= ((v >>> pos--) & 1) << i;
            if (i < bBits) b |= ((v >>> pos--) & 1) << i;
        }
        return new long[]{a, b};
    }

    /** Bitwise CRC (MSB first): CRC-8 poly x^8+x^2+x+1, CRC-7 x^7+x^3+1, CRC-6 x^6+x+1, tier-specific seed. */
    static long crc(long payload, int bits, Tier tier) {
        int width = tier.crcBits;
        long poly = width == 8 ? 0x07 : width == 7 ? 0x09 : 0x03;
        long top = 1L << (width - 1);
        long mask = (1L << width) - 1;
        long crc = tier.crcInit & mask;
        for (int i = bits - 1; i >= 0; i--) {
            long bit = (payload >>> i) & 1;
            boolean msb = (crc & top) != 0;
            crc = (crc << 1) & mask;
            if (msb ^ (bit == 1)) crc ^= poly;
        }
        return crc;
    }

    private static String toSyllables(long bits, int count) {
        char[] out = new char[count * 2];
        for (int i = count - 1; i >= 0; i--) {
            int s = (int) (bits & 63);
            bits >>>= SYLLABLE_BITS;
            out[i * 2] = CONSONANTS.charAt(s >>> 2);
            out[i * 2 + 1] = VOWELS.charAt(s & 3);
        }
        return new String(out);
    }
}

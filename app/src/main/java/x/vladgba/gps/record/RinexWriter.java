package x.vladgba.gps.record;

import android.location.GnssClock;
import android.location.GnssMeasurement;
import android.location.GnssMeasurementsEvent;
import android.location.GnssStatus;
import android.os.Build;

import x.vladgba.gps.gnss.Constellations;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Calendar;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.TreeMap;

/**
 * RINEX 3.04 observation file from Android raw measurements (code, phase, Doppler, C/N0).
 *
 * <p>The header needs facts only known at the end (first epoch, GLONASS frequency channels,
 * systems seen, approximate position), so epochs are written to a body file and the final
 * file is assembled in {@link #finish}. Pseudoranges follow the standard Android recipe: receiver
 * time from GnssClock (full bias + bias), reduced to the constellation's time of week/day, minus
 * the received satellite time. Integer nanoseconds are kept in {@code long}: a {@code double}
 * holding full GPS time has ~256 ns resolution (≈77 m).
 */
final class RinexWriter {

    private static final double SPEED_OF_LIGHT = 299_792_458.0;
    private static final long NS_WEEK = 604_800L * 1_000_000_000L;
    private static final long NS_DAY = 86_400L * 1_000_000_000L;
    private static final long NS_SECOND = 1_000_000_000L;
    private static final long BDS_OFFSET_NS = 14 * NS_SECOND;
    private static final long LEAP_SECONDS_NS = 18 * NS_SECOND;
    private static final long GPS_EPOCH_UNIX_MS = 315_964_800_000L;

    /** Observation types per system, in header order (4 per signal: C, L, D, S). */
    private static final Map<Character, String[]> SIGNALS = new LinkedHashMap<>();

    static {
        SIGNALS.put('G', new String[]{"1C", "5Q"});
        SIGNALS.put('R', new String[]{"1C"});
        SIGNALS.put('E', new String[]{"1C", "5Q", "7Q"});
        SIGNALS.put('C', new String[]{"2I", "1P", "5P"});
        SIGNALS.put('J', new String[]{"1C", "5Q"});
        SIGNALS.put('I', new String[]{"5A"});
        SIGNALS.put('S', new String[]{"1C"});
    }

    private final File bodyFile;
    private final File outputFile;
    private final Writer body;
    private final Map<Character, Boolean> systemsSeen = new TreeMap<>();
    private final Map<Integer, Integer> glonassChannels = new TreeMap<>();
    private long firstEpochGpsNanos = Long.MIN_VALUE;
    private int epochs;
    private double[] approxEcef;

    RinexWriter(File dir, long startUtcMs) throws IOException {
        bodyFile = new File(dir, ".rinex.body");
        outputFile = new File(dir, fileName(startUtcMs));
        body = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(bodyFile), StandardCharsets.US_ASCII));
    }

    int getEpochs() {
        return epochs;
    }

    void setApproxPosition(double latDeg, double lonDeg, double altM) {
        double a = 6378137.0;
        double f = 1 / 298.257223563;
        double e2 = f * (2 - f);
        double lat = Math.toRadians(latDeg);
        double lon = Math.toRadians(lonDeg);
        double n = a / Math.sqrt(1 - e2 * Math.sin(lat) * Math.sin(lat));
        approxEcef = new double[]{
                (n + altM) * Math.cos(lat) * Math.cos(lon),
                (n + altM) * Math.cos(lat) * Math.sin(lon),
                (n * (1 - e2) + altM) * Math.sin(lat)};
    }

    void addEpoch(GnssMeasurementsEvent event) throws IOException {
        GnssClock clock = event.getClock();
        if (!clock.hasFullBiasNanos()) return; // receiver time unknown: no pseudoranges possible
        long gpsNanos = clock.getTimeNanos() - clock.getFullBiasNanos();
        double biasNanos = clock.hasBiasNanos() ? clock.getBiasNanos() : 0;

        Map<String, double[]> values = new TreeMap<>();
        Map<String, int[]> lli = new TreeMap<>();
        for (GnssMeasurement m : event.getMeasurements()) {
            char system = systemLetter(m.getConstellationType());
            String[] signals = SIGNALS.get(system);
            if (signals == null) continue;
            String satId = satelliteId(system, m.getSvid());
            if (satId == null) continue;
            String signal = signalCode(m);
            int slot = Arrays.asList(signals).indexOf(signal);
            if (slot < 0) continue;

            double[] v = values.get(satId);
            if (v == null) {
                v = new double[signals.length * 4];
                Arrays.fill(v, Double.NaN);
                values.put(satId, v);
                lli.put(satId, new int[signals.length * 4]);
            }
            double wavelength = wavelength(m);
            double pr = pseudorange(m, gpsNanos, biasNanos);
            v[slot * 4] = pr;
            if (!Double.isNaN(wavelength)) {
                int adrState = m.getAccumulatedDeltaRangeState();
                if ((adrState & GnssMeasurement.ADR_STATE_VALID) != 0) {
                    v[slot * 4 + 1] = m.getAccumulatedDeltaRangeMeters() / wavelength;
                    boolean slip = (adrState & (GnssMeasurement.ADR_STATE_RESET | GnssMeasurement.ADR_STATE_CYCLE_SLIP)) != 0;
                    lli.get(satId)[slot * 4 + 1] = slip ? 1 : 0;
                }
                v[slot * 4 + 2] = -m.getPseudorangeRateMetersPerSecond() / wavelength;
            }
            v[slot * 4 + 3] = m.getCn0DbHz();
            systemsSeen.put(system, true);
            if (system == 'R' && m.hasCarrierFrequencyHz()) {
                int k = (int) Math.round((m.getCarrierFrequencyHz() - 1602e6) / 562.5e3);
                glonassChannels.put(m.getSvid(), k);
            }
        }
        if (values.isEmpty()) return;

        long epochNanos = gpsNanos - Math.round(biasNanos);
        if (firstEpochGpsNanos == Long.MIN_VALUE) firstEpochGpsNanos = epochNanos;
        body.write(epochLine(epochNanos, values.size()));
        for (Map.Entry<String, double[]> e : values.entrySet()) {
            StringBuilder line = new StringBuilder(e.getKey());
            double[] v = e.getValue();
            int[] flags = lli.get(e.getKey());
            for (int i = 0; i < v.length; i++) {
                if (Double.isNaN(v[i]) || Math.abs(v[i]) >= 1e10) {
                    line.append("                ");
                } else {
                    line.append(String.format(Locale.ROOT, "%14.3f", v[i]));
                    line.append(flags[i] != 0 ? '1' : ' ').append(' ');
                }
            }
            body.write(rtrim(line));
            body.write('\n');
        }
        epochs++;
        if (epochs % 10 == 0) body.flush();
    }

    /** Writes header + body to the final .rnx file and removes the temporary body. */
    void finish() throws IOException {
        body.close();
        if (epochs == 0) {
            //noinspection ResultOfMethodCallIgnored
            bodyFile.delete();
            return;
        }
        try (Writer out = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(outputFile), StandardCharsets.US_ASCII));
             BufferedReader in = new BufferedReader(new InputStreamReader(new FileInputStream(bodyFile), StandardCharsets.US_ASCII))) {
            writeHeader(out);
            char[] buffer = new char[16 * 1024];
            int n;
            while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
        }
        //noinspection ResultOfMethodCallIgnored
        bodyFile.delete();
    }

    // ---- Measurement maths --------------------------------------------------------------------

    /** Pseudorange in metres, NaN when the satellite time is not resolved yet. */
    static double pseudorange(GnssMeasurement m, long gpsNanos, double biasNanos) {
        int state = m.getState();
        int c = m.getConstellationType();
        long period;
        long tRx;
        switch (c) {
            case GnssStatus.CONSTELLATION_GLONASS:
                if ((state & (GnssMeasurement.STATE_GLO_TOD_DECODED | STATE_GLO_TOD_KNOWN)) == 0) return Double.NaN;
                period = NS_DAY;
                tRx = Math.floorMod(gpsNanos - LEAP_SECONDS_NS + 3 * 3600 * NS_SECOND, NS_DAY);
                break;
            case GnssStatus.CONSTELLATION_BEIDOU:
                if ((state & (GnssMeasurement.STATE_TOW_DECODED | STATE_TOW_KNOWN)) == 0) return Double.NaN;
                period = NS_WEEK;
                tRx = Math.floorMod(gpsNanos - BDS_OFFSET_NS, NS_WEEK);
                break;
            default: // GPS, Galileo, QZSS, NavIC, SBAS: GPS-aligned time of week
                if ((state & (GnssMeasurement.STATE_TOW_DECODED | STATE_TOW_KNOWN)) == 0) return Double.NaN;
                period = NS_WEEK;
                tRx = Math.floorMod(gpsNanos, NS_WEEK);
                break;
        }
        if ((state & GnssMeasurement.STATE_CODE_LOCK) == 0) return Double.NaN;
        long tTx = m.getReceivedSvTimeNanos();
        double prNanos = (tRx - tTx) + m.getTimeOffsetNanos() - biasNanos;
        if (prNanos > period / 2.0) prNanos -= period;
        if (prNanos < -period / 2.0) prNanos += period;
        double pr = prNanos * 1e-9 * SPEED_OF_LIGHT;
        return pr > 1.0e7 && pr < 5.0e7 ? pr : Double.NaN;
    }

    // STATE_*_KNOWN were added in API 28; values copied so older devices simply never set them.
    private static final int STATE_TOW_KNOWN = 1 << 14;
    private static final int STATE_GLO_TOD_KNOWN = 1 << 15;

    private static double wavelength(GnssMeasurement m) {
        if (!m.hasCarrierFrequencyHz()) {
            return m.getConstellationType() == GnssStatus.CONSTELLATION_GLONASS ? Double.NaN
                    : m.getConstellationType() == GnssStatus.CONSTELLATION_BEIDOU ? SPEED_OF_LIGHT / 1561.098e6
                    : SPEED_OF_LIGHT / 1575.42e6;
        }
        return SPEED_OF_LIGHT / m.getCarrierFrequencyHz();
    }

    /** Two-character RINEX signal code (band + attribute) for a measurement. */
    static String signalCode(GnssMeasurement m) {
        float hz = m.hasCarrierFrequencyHz() ? m.getCarrierFrequencyHz() : Float.NaN;
        String band = Constellations.band(m.getConstellationType(), hz);
        switch (m.getConstellationType()) {
            case GnssStatus.CONSTELLATION_GPS:
            case GnssStatus.CONSTELLATION_QZSS:
                return "L5".equals(band) ? "5Q" : band == null || "L1".equals(band) ? "1C" : null;
            case GnssStatus.CONSTELLATION_SBAS:
                return band == null || "L1".equals(band) ? "1C" : null;
            case GnssStatus.CONSTELLATION_GLONASS:
                return band == null || "G1".equals(band) ? "1C" : null;
            case GnssStatus.CONSTELLATION_GALILEO:
                if ("E5a".equals(band)) return "5Q";
                if ("E5b".equals(band)) return "7Q";
                return band == null || "E1".equals(band) ? "1C" : null;
            case GnssStatus.CONSTELLATION_BEIDOU:
                if ("B1C".equals(band)) return "1P";
                if ("B2a".equals(band)) return "5P";
                return band == null || "B1I".equals(band) ? "2I" : null;
            case GnssStatus.CONSTELLATION_IRNSS:
                return "5A";
            default:
                return null;
        }
    }

    static char systemLetter(int constellation) {
        switch (constellation) {
            case GnssStatus.CONSTELLATION_GPS: return 'G';
            case GnssStatus.CONSTELLATION_GLONASS: return 'R';
            case GnssStatus.CONSTELLATION_GALILEO: return 'E';
            case GnssStatus.CONSTELLATION_BEIDOU: return 'C';
            case GnssStatus.CONSTELLATION_QZSS: return 'J';
            case GnssStatus.CONSTELLATION_IRNSS: return 'I';
            case GnssStatus.CONSTELLATION_SBAS: return 'S';
            default: return '?';
        }
    }

    /** RINEX satellite id, null when it cannot be expressed (GLONASS with unknown slot). */
    static String satelliteId(char system, int svid) {
        int prn = svid;
        if (system == 'R' && (svid < 1 || svid > 24)) return null;
        if (system == 'J') prn = svid >= 193 ? svid - 192 : svid;
        if (system == 'S') prn = svid >= 100 ? svid - 100 : svid;
        if (prn < 1 || prn > 99) return null;
        return String.format(Locale.ROOT, "%c%02d", system, prn);
    }

    // ---- Formatting ---------------------------------------------------------------------------

    private static String epochLine(long gpsNanos, int satellites) {
        Calendar c = gpsCalendar(gpsNanos);
        double seconds = c.get(Calendar.SECOND) + Math.floorMod(gpsNanos, NS_SECOND) / 1e9;
        return String.format(Locale.ROOT, "> %4d %02d %02d %02d %02d%11.7f  0%3d%n",
                c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH),
                c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE), seconds, satellites)
                .replace(System.lineSeparator(), "\n");
    }

    /** Calendar fields of a GPS-time instant (no leap seconds applied: RINEX uses GPS time). */
    private static Calendar gpsCalendar(long gpsNanos) {
        Calendar c = Calendar.getInstance(TimeZone.getTimeZone("UTC"), Locale.ROOT);
        c.setTimeInMillis(GPS_EPOCH_UNIX_MS + Math.floorDiv(gpsNanos, NS_SECOND) * 1000);
        return c;
    }

    private void writeHeader(Writer out) throws IOException {
        header(out, String.format(Locale.ROOT, "%9.2f%11s%-20s%-20s", 3.04, "", "OBSERVATION DATA", "M"),
                "RINEX VERSION / TYPE");
        Calendar now = Calendar.getInstance(TimeZone.getTimeZone("UTC"), Locale.ROOT);
        header(out, String.format(Locale.ROOT, "%-20s%-20s%04d%02d%02d %02d%02d%02d UTC ", "pGPS", "",
                now.get(Calendar.YEAR), now.get(Calendar.MONTH) + 1, now.get(Calendar.DAY_OF_MONTH),
                now.get(Calendar.HOUR_OF_DAY), now.get(Calendar.MINUTE), now.get(Calendar.SECOND)),
                "PGM / RUN BY / DATE");
        header(out, "GPST", "MARKER NAME");
        header(out, "NON_GEODETIC", "MARKER TYPE");
        header(out, String.format(Locale.ROOT, "%-20s%-40s", "unknown", "unknown"), "OBSERVER / AGENCY");
        String model = (Build.MANUFACTURER + " " + Build.MODEL).trim();
        header(out, String.format(Locale.ROOT, "%-20s%-20.20s%-20.20s", "unknown", model, "Android " + Build.VERSION.RELEASE),
                "REC # / TYPE / VERS");
        header(out, String.format(Locale.ROOT, "%-20s%-20s", "unknown", "internal"), "ANT # / TYPE");
        double[] xyz = approxEcef != null ? approxEcef : new double[3];
        header(out, String.format(Locale.ROOT, "%14.4f%14.4f%14.4f", xyz[0], xyz[1], xyz[2]), "APPROX POSITION XYZ");
        header(out, String.format(Locale.ROOT, "%14.4f%14.4f%14.4f", 0.0, 0.0, 0.0), "ANTENNA: DELTA H/E/N");
        for (Map.Entry<Character, String[]> e : SIGNALS.entrySet()) {
            if (!systemsSeen.containsKey(e.getKey())) continue;
            writeObsTypes(out, e.getKey(), e.getValue());
        }
        header(out, "DBHZ", "SIGNAL STRENGTH UNIT");
        Calendar first = gpsCalendar(firstEpochGpsNanos);
        double seconds = first.get(Calendar.SECOND) + Math.floorMod(firstEpochGpsNanos, NS_SECOND) / 1e9;
        header(out, String.format(Locale.ROOT, "%6d%6d%6d%6d%6d%13.7f     GPS", first.get(Calendar.YEAR),
                first.get(Calendar.MONTH) + 1, first.get(Calendar.DAY_OF_MONTH), first.get(Calendar.HOUR_OF_DAY),
                first.get(Calendar.MINUTE), seconds), "TIME OF FIRST OBS");
        for (Map.Entry<Character, String[]> e : SIGNALS.entrySet()) {
            if (!systemsSeen.containsKey(e.getKey())) continue;
            for (String s : e.getValue()) {
                header(out, String.format(Locale.ROOT, "%c L%s %8.5f", e.getKey(), s, 0.0), "SYS / PHASE SHIFT");
            }
        }
        if (systemsSeen.containsKey('R')) {
            List<Map.Entry<Integer, Integer>> slots = new java.util.ArrayList<>(glonassChannels.entrySet());
            for (int i = 0; i < Math.max(1, slots.size()); i += 8) {
                StringBuilder sb = new StringBuilder(i == 0 ? String.format(Locale.ROOT, "%3d ", slots.size()) : "    ");
                for (int j = i; j < Math.min(i + 8, slots.size()); j++) {
                    sb.append(String.format(Locale.ROOT, "R%02d %2d ", slots.get(j).getKey(), slots.get(j).getValue()));
                }
                header(out, sb.toString(), "GLONASS SLOT / FRQ #");
            }
            header(out, " C1C    0.000 C1P    0.000 C2C    0.000 C2P    0.000", "GLONASS COD/PHS/BIS");
        }
        header(out, "", "END OF HEADER");
    }

    private static void writeObsTypes(Writer out, char system, String[] signals) throws IOException {
        StringBuilder types = new StringBuilder();
        int count = 0;
        for (String s : signals) {
            for (char kind : new char[]{'C', 'L', 'D', 'S'}) {
                types.append(' ').append(kind).append(s);
                count++;
            }
        }
        String all = types.toString();
        int perLine = 13 * 4; // 13 types of " XXX"
        for (int i = 0; i < all.length(); i += perLine) {
            String prefix = i == 0 ? String.format(Locale.ROOT, "%c  %3d", system, count) : "      ";
            header(out, prefix + all.substring(i, Math.min(all.length(), i + perLine)), "SYS / # / OBS TYPES");
        }
    }

    private static void header(Writer out, String content, String label) throws IOException {
        String c = content.length() > 60 ? content.substring(0, 60) : content;
        out.write(String.format(Locale.ROOT, "%-60s%-20s", c, label).replaceAll("\\s+$", ""));
        out.write('\n');
    }

    private static String rtrim(CharSequence s) {
        int end = s.length();
        while (end > 0 && s.charAt(end - 1) == ' ') end--;
        return s.subSequence(0, end).toString();
    }

    /** RINEX 3 long file name, e.g. GPST00XXX_R_20262921415_00U_01S_MO.rnx. */
    private static String fileName(long utcMs) {
        Calendar c = Calendar.getInstance(TimeZone.getTimeZone("UTC"), Locale.ROOT);
        c.setTimeInMillis(utcMs);
        return String.format(Locale.ROOT, "GPST00XXX_R_%04d%03d%02d%02d_00U_01S_MO.rnx",
                c.get(Calendar.YEAR), c.get(Calendar.DAY_OF_YEAR), c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE));
    }
}

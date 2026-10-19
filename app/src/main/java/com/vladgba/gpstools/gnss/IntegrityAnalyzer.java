package com.vladgba.gpstools.gnss;

import android.location.GnssStatus;

import com.vladgba.gpstools.gnss.IntegrityReport.Check;
import com.vladgba.gpstools.gnss.IntegrityReport.CheckId;
import com.vladgba.gpstools.gnss.IntegrityReport.Level;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Heuristic jamming and spoofing detection from what a phone exposes: satellite C/N0 and
 * geometry, AGC levels from raw measurements, the GNSS fix, the network (Wi-Fi/cell) fix and the
 * system clock. None of these proves an attack on its own; the verdicts combine them and stay
 * conservative. Thresholds are first-pass values meant to be tuned with field data.
 *
 * <p>Fed from the main thread by {@link GnssMonitor}; not thread-safe.
 */
public final class IntegrityAnalyzer {

    // ---- Tunables -------------------------------------------------------------------------
    /** Samples needed before a baseline is trusted. */
    static final int WARMUP_SAMPLES = 10;
    /** A baseline only learns from epochs with at least this many signals (skips cold start). */
    static final int MIN_SIGNALS_FOR_BASELINE = 4;
    static final int MIN_SIGNALS_FOR_STATISTICS = 6;
    static final int TOP_N = 4;

    static final double AGC_WARN_DB = 4;
    static final double AGC_ALERT_DB = 8;
    static final double CN0_DROP_WARN_DB = 6;
    static final double CN0_DROP_ALERT_DB = 10;
    static final double SIGNAL_LOSS_WARN = 0.5;
    static final double SIGNAL_LOSS_ALERT = 0.8;

    static final double UNIFORM_STD_ALERT_DB = 1.5;
    static final double UNIFORM_STD_WARN_DB = 3.0;
    /** Real skies show a positive elevation/C/N0 correlation; clearly negative is suspicious. */
    static final double CORRELATION_WARN = -0.3;
    /** Top-4 C/N0 below this means an obstructed sky (indoors, dense trees). */
    static final double OPEN_SKY_TOP_CN0_DB = 33;
    static final float STRONG_CN0_DB = 50f;
    static final int STRONG_COUNT_WARN = 4;

    static final double NETWORK_WARN_M = 2_000;
    static final double NETWORK_ALERT_M = 10_000;
    static final long NETWORK_MAX_AGE_MS = 10 * 60_000L;

    static final long CLOCK_WARN_MS = 5_000;
    static final long CLOCK_ALERT_MS = 60_000;
    /** GNSS time advancing differently from the monotonic clock by more than this is a jump. */
    static final long TIME_JUMP_MS = 2_000;

    static final double JUMP_MIN_DISTANCE_M = 300;
    static final double JUMP_MIN_SPEED_MPS = 150;
    static final long LATCH_ALERT_MS = 60_000;
    static final long LATCH_WARN_MS = 10 * 60_000L;

    static final long AGC_MAX_AGE_MS = 10_000;
    static final long HISTORY_MS = 180_000;

    private static final double LEARN_RATE = 0.02;
    /** Slow learning while degraded so a lasting change (going indoors) is absorbed in minutes. */
    private static final double LEARN_RATE_DEGRADED = 0.002;
    private static final double AGC_SMOOTHING = 0.5;
    private static final double TOP_CN0_SMOOTHING = 0.3;
    private static final double EARTH_RADIUS_M = 6_371_008.8;

    // ---- State ----------------------------------------------------------------------------
    private final Baseline topCn0Baseline = new Baseline();
    private final Baseline signalCountBaseline = new Baseline();
    private final Map<String, Baseline> agcBaselines = new TreeMap<>();
    private final Map<String, Double> agcCurrent = new TreeMap<>();
    private long agcUpdatedAt = Long.MIN_VALUE;

    private final TimeSeries cn0History = new TimeSeries(HISTORY_MS);
    private final TimeSeries agcHistory = new TimeSeries(HISTORY_MS);
    private String agcHistoryBand;

    private boolean haveEpoch;
    private boolean haveEpochBefore;
    private double topCn0 = Double.NaN;
    private int signalCount;
    private double cn0Std = Double.NaN;
    private int cn0StdSamples;
    private double elevationCorrelation = Double.NaN;
    private int correlationSamples;
    private int strongCount;
    private Set<Integer> currentSystems = new HashSet<>();
    private int maxSystemsSeen;

    private boolean haveFix;
    private boolean mock;
    private double fixLat;
    private double fixLon;
    private long fixElapsedMs;
    private long fixTimeMs;
    private long clockOffsetMs;

    private boolean haveNetwork;
    private double netLat;
    private double netLon;
    private float netAccuracy;
    private long netElapsedMs;

    private long jumpAt = Long.MIN_VALUE;
    private String jumpDescription;
    private long timeJumpAt = Long.MIN_VALUE;
    private String timeJumpDescription;

    // ---- Inputs ---------------------------------------------------------------------------

    public void onSatellites(List<SatelliteInfo> satellites, long nowMs) {
        haveEpoch = true;
        List<Float> cn0s = new ArrayList<>();
        List<double[]> elevationPairs = new ArrayList<>();
        Set<Integer> systems = new HashSet<>();
        int strong = 0;
        for (SatelliteInfo s : satellites) {
            if (s.cn0 <= 0) continue;
            cn0s.add(s.cn0);
            systems.add(s.constellation);
            if (s.cn0 >= STRONG_CN0_DB) strong++;
            if (s.hasSkyPosition() && s.elevation > 0) elevationPairs.add(new double[]{s.elevation, s.cn0});
        }
        signalCount = cn0s.size();
        strongCount = strong;
        currentSystems = systems;
        maxSystemsSeen = Math.max(maxSystemsSeen, systems.size());

        float[] sorted = new float[cn0s.size()];
        for (int i = 0; i < sorted.length; i++) sorted[i] = cn0s.get(i);
        Arrays.sort(sorted);
        double sum = 0;
        int n = Math.min(TOP_N, sorted.length);
        for (int i = 0; i < n; i++) sum += sorted[sorted.length - 1 - i];
        double rawTop = n == 0 ? 0 : sum / n;
        // Smooth over a few epochs: single-second dips (a hand over the antenna) are not jamming.
        topCn0 = Double.isNaN(topCn0) || !haveEpochBefore ? rawTop : topCn0 + (rawTop - topCn0) * TOP_CN0_SMOOTHING;
        haveEpochBefore = true;

        if (signalCount >= MIN_SIGNALS_FOR_BASELINE) {
            topCn0Baseline.update(topCn0, topCn0Baseline.ready()
                    && topCn0Baseline.get() - topCn0 >= CN0_DROP_WARN_DB);
            signalCountBaseline.update(signalCount, signalCountBaseline.ready()
                    && signalCount < signalCountBaseline.get() * (1 - SIGNAL_LOSS_WARN));
        }

        cn0StdSamples = sorted.length;
        cn0Std = sorted.length >= MIN_SIGNALS_FOR_STATISTICS ? standardDeviation(sorted) : Double.NaN;
        correlationSamples = elevationPairs.size();
        elevationCorrelation = elevationPairs.size() >= MIN_SIGNALS_FOR_STATISTICS
                ? pearson(elevationPairs) : Double.NaN;

        // Skip the empty epochs of a cold start; once learned, a collapse to 0 is worth plotting.
        if (signalCount > 0 || topCn0Baseline.ready()) cn0History.add(nowMs, topCn0);
    }

    /** AGC level in dB per band key ("GPS L1"…). More negative means more in-band power. */
    public void onAgc(Map<String, Double> levels, long nowMs) {
        if (levels.isEmpty()) return;
        for (Map.Entry<String, Double> e : levels.entrySet()) {
            Double previous = agcCurrent.get(e.getKey());
            double smoothed = previous == null ? e.getValue()
                    : previous + (e.getValue() - previous) * AGC_SMOOTHING;
            agcCurrent.put(e.getKey(), smoothed);
            Baseline b = agcBaselines.get(e.getKey());
            if (b == null) {
                b = new Baseline();
                agcBaselines.put(e.getKey(), b);
            }
            b.update(smoothed, b.ready() && b.get() - smoothed >= AGC_WARN_DB);
        }
        agcUpdatedAt = nowMs;
        if (agcHistoryBand == null || !agcCurrent.containsKey(agcHistoryBand)) {
            agcHistoryBand = agcCurrent.containsKey("GPS L1") ? "GPS L1" : agcCurrent.keySet().iterator().next();
            agcHistory.clear();
        }
        agcHistory.add(nowMs, agcCurrent.get(agcHistoryBand));
    }

    /**
     * @param fixElapsedMs   monotonic time of the fix (Location#getElapsedRealtimeNanos / 1e6)
     * @param fixTimeMs      GNSS UTC time of the fix (Location#getTime)
     * @param clockOffsetMs  GNSS time minus system wall clock at the moment of the fix
     */
    public void onGnssFix(double lat, double lon, boolean isMock, long fixElapsedMs, long fixTimeMs,
                          long clockOffsetMs, long nowMs) {
        if (haveFix) {
            long dtMs = fixElapsedMs - this.fixElapsedMs;
            if (dtMs > 0 && dtMs < 30_000) {
                double distance = distanceMeters(fixLat, fixLon, lat, lon);
                double speed = distance / (dtMs / 1000.0);
                if (distance >= JUMP_MIN_DISTANCE_M && speed >= JUMP_MIN_SPEED_MPS) {
                    jumpAt = nowMs;
                    jumpDescription = String.format(Locale.ROOT, "%s in %.1f s", distance(distance), dtMs / 1000.0);
                }
                long gnssAdvance = fixTimeMs - this.fixTimeMs;
                if (Math.abs(gnssAdvance - dtMs) > TIME_JUMP_MS) {
                    timeJumpAt = nowMs;
                    timeJumpDescription = String.format(Locale.ROOT, "time jumped %+.1f s",
                            (gnssAdvance - dtMs) / 1000.0);
                }
            }
        }
        haveFix = true;
        mock = isMock;
        fixLat = lat;
        fixLon = lon;
        this.fixElapsedMs = fixElapsedMs;
        this.fixTimeMs = fixTimeMs;
        this.clockOffsetMs = clockOffsetMs;
    }

    public void onNetworkFix(double lat, double lon, float accuracyM, long fixElapsedMs) {
        haveNetwork = true;
        netLat = lat;
        netLon = lon;
        netAccuracy = accuracyM;
        netElapsedMs = fixElapsedMs;
    }

    /** Forget everything learned, e.g. after moving from indoors to open sky. */
    public void reset() {
        topCn0Baseline.reset();
        signalCountBaseline.reset();
        agcBaselines.clear();
        agcCurrent.clear();
        agcUpdatedAt = Long.MIN_VALUE;
        cn0History.clear();
        agcHistory.clear();
        agcHistoryBand = null;
        haveEpoch = false;
        haveEpochBefore = false;
        topCn0 = Double.NaN;
        maxSystemsSeen = 0;
        currentSystems = new HashSet<>();
        haveFix = false;
        jumpAt = Long.MIN_VALUE;
        timeJumpAt = Long.MIN_VALUE;
    }

    // ---- Evaluation -----------------------------------------------------------------------

    public IntegrityReport evaluate(long nowMs) {
        cn0History.trim(nowMs);
        agcHistory.trim(nowMs);

        List<Check> jamming = new ArrayList<>();
        Check agc = agcCheck(nowMs);
        Check cn0Drop = cn0DropCheck();
        Check loss = signalLossCheck();
        jamming.add(agc);
        jamming.add(cn0Drop);
        jamming.add(loss);

        List<Check> spoofing = new ArrayList<>();
        spoofing.add(mockCheck());
        spoofing.add(networkCheck(nowMs));
        spoofing.add(jumpCheck(nowMs));
        spoofing.add(clockCheck(nowMs));
        spoofing.add(uniformityCheck());
        spoofing.add(correlationCheck());
        spoofing.add(strongCheck());
        spoofing.add(constellationCheck());

        return new IntegrityReport(jammingVerdict(agc, cn0Drop, loss), spoofingVerdict(spoofing), jamming, spoofing);
    }

    /**
     * AGC is what separates a jammer (more in-band power, gain goes down) from an obstruction
     * (signals weaker, noise unchanged). Without it, C/N0 alone can only say "possible".
     */
    static Level jammingVerdict(Check agc, Check cn0Drop, Check loss) {
        Level degradation = max(cn0Drop.level, loss.level);
        switch (agc.level) {
            case ALERT:
                return Level.ALERT;
            case WARN:
                return degradation.compareTo(Level.WARN) >= 0 ? Level.ALERT : Level.WARN;
            case OK:
                // Signals fading with a quiet noise floor is an obstruction, not jamming.
                return cn0Drop.level == Level.ALERT && loss.level == Level.ALERT ? Level.WARN : Level.OK;
            default:
                if (degradation == Level.UNKNOWN) return Level.UNKNOWN;
                return degradation.compareTo(Level.WARN) >= 0 ? Level.WARN : Level.OK;
        }
    }

    static Level spoofingVerdict(List<Check> checks) {
        int score = 0;
        boolean anyKnown = false;
        for (Check c : checks) {
            if (c.level != Level.UNKNOWN) anyKnown = true;
            if (c.id == CheckId.MOCK_LOCATION && c.level == Level.ALERT) return Level.ALERT;
            if (c.level == Level.ALERT) score += 2;
            else if (c.level == Level.WARN) score += 1;
        }
        if (!anyKnown) return Level.UNKNOWN;
        if (score >= 3) return Level.ALERT;
        return score >= 1 ? Level.WARN : Level.OK;
    }

    private Check agcCheck(long now) {
        if (agcCurrent.isEmpty() || now - agcUpdatedAt > AGC_MAX_AGE_MS) {
            return new Check(CheckId.AGC, Level.UNKNOWN, "Not reported by this device");
        }
        String worstBand = null;
        double worstDelta = Double.POSITIVE_INFINITY;
        int learning = 0;
        for (Map.Entry<String, Double> e : agcCurrent.entrySet()) {
            Baseline b = agcBaselines.get(e.getKey());
            if (b == null || !b.ready()) {
                learning++;
                continue;
            }
            double delta = e.getValue() - b.get();
            if (delta < worstDelta) {
                worstDelta = delta;
                worstBand = e.getKey();
            }
        }
        if (worstBand == null) {
            return new Check(CheckId.AGC, Level.UNKNOWN, learningText(firstAgcSamples()));
        }
        Level level = -worstDelta >= AGC_ALERT_DB ? Level.ALERT : -worstDelta >= AGC_WARN_DB ? Level.WARN : Level.OK;
        String bands = agcCurrent.size() > 1 ? String.format(Locale.ROOT, " (worst of %d bands)", agcCurrent.size() - learning) : "";
        return new Check(CheckId.AGC, level,
                String.format(Locale.ROOT, "%s %+.1f dB vs baseline%s", worstBand, worstDelta, bands));
    }

    private int firstAgcSamples() {
        int max = 0;
        for (Baseline b : agcBaselines.values()) max = Math.max(max, b.samples);
        return max;
    }

    private Check cn0DropCheck() {
        if (!haveEpoch) return new Check(CheckId.CN0_DROP, Level.UNKNOWN, "No satellite data");
        if (!topCn0Baseline.ready()) {
            return new Check(CheckId.CN0_DROP, Level.UNKNOWN, learningText(topCn0Baseline.samples));
        }
        double drop = topCn0Baseline.get() - topCn0;
        Level level = drop >= CN0_DROP_ALERT_DB ? Level.ALERT : drop >= CN0_DROP_WARN_DB ? Level.WARN : Level.OK;
        return new Check(CheckId.CN0_DROP, level, String.format(Locale.ROOT,
                "Top-%d %.1f dB-Hz (baseline %.1f)", TOP_N, topCn0, topCn0Baseline.get()));
    }

    private Check signalLossCheck() {
        if (!haveEpoch) return new Check(CheckId.SIGNAL_LOSS, Level.UNKNOWN, "No satellite data");
        if (!signalCountBaseline.ready()) {
            return new Check(CheckId.SIGNAL_LOSS, Level.UNKNOWN, learningText(signalCountBaseline.samples));
        }
        double usual = signalCountBaseline.get();
        double lost = usual <= 0 ? 0 : 1 - signalCount / usual;
        Level level = lost >= SIGNAL_LOSS_ALERT ? Level.ALERT : lost >= SIGNAL_LOSS_WARN ? Level.WARN : Level.OK;
        return new Check(CheckId.SIGNAL_LOSS, level,
                String.format(Locale.ROOT, "%d tracked, usually %.0f", signalCount, usual));
    }

    private Check mockCheck() {
        if (!haveFix) return new Check(CheckId.MOCK_LOCATION, Level.UNKNOWN, "No fix yet");
        return mock
                ? new Check(CheckId.MOCK_LOCATION, Level.ALERT, "Mock location active")
                : new Check(CheckId.MOCK_LOCATION, Level.OK, "Real provider");
    }

    private Check networkCheck(long now) {
        if (!haveFix) return new Check(CheckId.NETWORK_DISTANCE, Level.UNKNOWN, "No GNSS fix yet");
        if (!haveNetwork || now - netElapsedMs > NETWORK_MAX_AGE_MS) {
            return new Check(CheckId.NETWORK_DISTANCE, Level.UNKNOWN, "No recent network location");
        }
        double d = distanceMeters(fixLat, fixLon, netLat, netLon);
        double warn = Math.max(NETWORK_WARN_M, 3.0 * netAccuracy);
        double alert = Math.max(NETWORK_ALERT_M, 5.0 * netAccuracy);
        Level level = d >= alert ? Level.ALERT : d >= warn ? Level.WARN : Level.OK;
        return new Check(CheckId.NETWORK_DISTANCE, level,
                String.format(Locale.ROOT, "%s apart (network ± %s)", distance(d), distance(netAccuracy)));
    }

    private Check jumpCheck(long now) {
        if (!haveFix && jumpAt == Long.MIN_VALUE) {
            return new Check(CheckId.POSITION_JUMP, Level.UNKNOWN, "No GNSS fix yet");
        }
        return latched(CheckId.POSITION_JUMP, jumpAt, jumpDescription, now, "No jumps");
    }

    private Check clockCheck(long now) {
        if (!haveFix) return new Check(CheckId.CLOCK, Level.UNKNOWN, "No GNSS fix yet");
        Check jump = latched(CheckId.CLOCK, timeJumpAt, timeJumpDescription, now, null);
        long abs = Math.abs(clockOffsetMs);
        Level offsetLevel = abs >= CLOCK_ALERT_MS ? Level.ALERT : abs >= CLOCK_WARN_MS ? Level.WARN : Level.OK;
        String offset = String.format(Locale.ROOT, "GNSS %+.1f s vs phone clock", clockOffsetMs / 1000.0);
        if (jump.level.compareTo(offsetLevel) > 0) {
            return new Check(CheckId.CLOCK, jump.level, offset + "; " + jump.value);
        }
        return new Check(CheckId.CLOCK, offsetLevel, offset);
    }

    /** ALERT for a minute after the event, WARN for ten, then back to OK. */
    private static Check latched(CheckId id, long at, String description, long now, String okText) {
        if (at != Long.MIN_VALUE) {
            long age = now - at;
            if (age <= LATCH_ALERT_MS) return new Check(id, Level.ALERT, description);
            if (age <= LATCH_WARN_MS) {
                return new Check(id, Level.WARN, String.format(Locale.ROOT, "%s, %d min ago", description, age / 60_000 + 1));
            }
        }
        return new Check(id, Level.OK, okText);
    }

    private Check uniformityCheck() {
        if (Double.isNaN(cn0Std)) {
            return new Check(CheckId.CN0_UNIFORM, Level.UNKNOWN, needsSignals(cn0StdSamples));
        }
        // Indoors the few signals that get through are all weak and similar; a spoofer's are
        // strong. Only judge the spread at open-sky signal levels.
        if (topCn0 < OPEN_SKY_TOP_CN0_DB) {
            return new Check(CheckId.CN0_UNIFORM, Level.UNKNOWN, String.format(Locale.ROOT,
                    "σ = %.1f dB · signals too weak (indoors?) to judge", cn0Std));
        }
        Level level = cn0Std < UNIFORM_STD_ALERT_DB ? Level.ALERT : cn0Std < UNIFORM_STD_WARN_DB ? Level.WARN : Level.OK;
        return new Check(CheckId.CN0_UNIFORM, level,
                String.format(Locale.ROOT, "σ = %.1f dB over %d signals", cn0Std, cn0StdSamples));
    }

    private Check correlationCheck() {
        if (Double.isNaN(elevationCorrelation)) {
            return new Check(CheckId.CN0_ELEVATION, Level.UNKNOWN, needsSignals(correlationSamples));
        }
        // Indoors, walls attenuate by direction, not elevation: the correlation means nothing.
        if (topCn0 < OPEN_SKY_TOP_CN0_DB) {
            return new Check(CheckId.CN0_ELEVATION, Level.UNKNOWN, String.format(Locale.ROOT,
                    "r = %+.2f · signals too weak (indoors?) to judge", elevationCorrelation));
        }
        Level level = elevationCorrelation < CORRELATION_WARN ? Level.WARN : Level.OK;
        return new Check(CheckId.CN0_ELEVATION, level,
                String.format(Locale.ROOT, "r = %+.2f over %d signals", elevationCorrelation, correlationSamples));
    }

    private Check strongCheck() {
        if (!haveEpoch) return new Check(CheckId.CN0_STRONG, Level.UNKNOWN, "No satellite data");
        Level level = strongCount >= STRONG_COUNT_WARN ? Level.WARN : Level.OK;
        return new Check(CheckId.CN0_STRONG, level,
                String.format(Locale.ROOT, "%d signals ≥ %.0f dB-Hz", strongCount, STRONG_CN0_DB));
    }

    private Check constellationCheck() {
        if (!haveEpoch) return new Check(CheckId.CONSTELLATION_LOSS, Level.UNKNOWN, "No satellite data");
        boolean gpsOnly = currentSystems.size() == 1 && currentSystems.contains(GnssStatus.CONSTELLATION_GPS);
        if (gpsOnly && maxSystemsSeen >= 3) {
            return new Check(CheckId.CONSTELLATION_LOSS, Level.WARN,
                    String.format(Locale.ROOT, "Only GPS now, %d systems earlier", maxSystemsSeen));
        }
        return new Check(CheckId.CONSTELLATION_LOSS, Level.OK,
                String.format(Locale.ROOT, "%d systems tracked (max %d)", currentSystems.size(), maxSystemsSeen));
    }

    // ---- History for the chart --------------------------------------------------------------

    public TimeSeries getCn0History() {
        return cn0History;
    }

    public TimeSeries getAgcHistory() {
        return agcHistory;
    }

    /** Band plotted in {@link #getAgcHistory()}, or null when the device reports no AGC. */
    public String getAgcHistoryBand() {
        return agcHistoryBand;
    }

    // ---- Math -------------------------------------------------------------------------------

    static double standardDeviation(float[] values) {
        double mean = 0;
        for (float v : values) mean += v;
        mean /= values.length;
        double variance = 0;
        for (float v : values) variance += (v - mean) * (v - mean);
        return Math.sqrt(variance / values.length);
    }

    /** Pearson correlation of pairs {x, y}; NaN when either variable is constant. */
    static double pearson(List<double[]> pairs) {
        int n = pairs.size();
        double mx = 0;
        double my = 0;
        for (double[] p : pairs) {
            mx += p[0];
            my += p[1];
        }
        mx /= n;
        my /= n;
        double sxy = 0;
        double sxx = 0;
        double syy = 0;
        for (double[] p : pairs) {
            double dx = p[0] - mx;
            double dy = p[1] - my;
            sxy += dx * dy;
            sxx += dx * dx;
            syy += dy * dy;
        }
        if (sxx == 0 || syy == 0) return Double.NaN;
        return sxy / Math.sqrt(sxx * syy);
    }

    /** Great-circle distance (haversine). */
    static double distanceMeters(double lat1, double lon1, double lat2, double lon2) {
        double p1 = Math.toRadians(lat1);
        double p2 = Math.toRadians(lat2);
        double dp = p2 - p1;
        double dl = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dp / 2) * Math.sin(dp / 2)
                + Math.cos(p1) * Math.cos(p2) * Math.sin(dl / 2) * Math.sin(dl / 2);
        return 2 * EARTH_RADIUS_M * Math.asin(Math.min(1, Math.sqrt(a)));
    }

    private static String distance(double meters) {
        return meters >= 1000
                ? String.format(Locale.ROOT, "%.1f km", meters / 1000)
                : String.format(Locale.ROOT, "%.0f m", meters);
    }

    private static String learningText(int samples) {
        return String.format(Locale.ROOT, "Learning baseline (%d/%d)", Math.min(samples, WARMUP_SAMPLES), WARMUP_SAMPLES);
    }

    private static String needsSignals(int have) {
        return String.format(Locale.ROOT, "Needs %d+ signals (%d)", MIN_SIGNALS_FOR_STATISTICS, have);
    }

    private static Level max(Level a, Level b) {
        return a.compareTo(b) >= 0 ? a : b;
    }

    /** Running mean during warm-up, then an exponential moving average. */
    static final class Baseline {
        private double value = Double.NaN;
        int samples;

        void update(double x, boolean degraded) {
            samples++;
            if (samples <= WARMUP_SAMPLES || Double.isNaN(value)) {
                value = Double.isNaN(value) ? x : value + (x - value) / samples;
            } else {
                value += (x - value) * (degraded ? LEARN_RATE_DEGRADED : LEARN_RATE);
            }
        }

        boolean ready() {
            return samples >= WARMUP_SAMPLES;
        }

        double get() {
            return value;
        }

        void reset() {
            value = Double.NaN;
            samples = 0;
        }
    }

    /** Groups AGC levels reported per measurement into one average per band key. */
    public static Map<String, Double> averageByBand(List<String> keys, List<Double> levels) {
        Map<String, double[]> acc = new HashMap<>();
        for (int i = 0; i < keys.size(); i++) {
            double[] a = acc.get(keys.get(i));
            if (a == null) {
                a = new double[2];
                acc.put(keys.get(i), a);
            }
            a[0] += levels.get(i);
            a[1]++;
        }
        Map<String, Double> result = new TreeMap<>();
        for (Map.Entry<String, double[]> e : acc.entrySet()) result.put(e.getKey(), e.getValue()[0] / e.getValue()[1]);
        return result;
    }
}

package com.vladgba.gpstools.gnss;

import java.util.Collections;
import java.util.List;

/** Result of one {@link IntegrityAnalyzer#evaluate(long)} call: per-check levels and verdicts. */
public final class IntegrityReport {

    /** Ordered by severity so levels can be compared with {@code compareTo}. */
    public enum Level { UNKNOWN, OK, WARN, ALERT }

    public enum CheckId {
        // Jamming
        AGC,
        CN0_DROP,
        SIGNAL_LOSS,
        // Spoofing
        MOCK_LOCATION,
        NETWORK_DISTANCE,
        POSITION_JUMP,
        CLOCK,
        CN0_UNIFORM,
        CN0_ELEVATION,
        CN0_STRONG,
        CONSTELLATION_LOSS,
    }

    public static final class Check {
        public final CheckId id;
        public final Level level;
        /** Short measured value, locale-neutral (e.g. "σ = 4.2 dB over 18 signals"). */
        public final String value;

        public Check(CheckId id, Level level, String value) {
            this.id = id;
            this.level = level;
            this.value = value;
        }
    }

    public final Level jamming;
    public final Level spoofing;
    public final List<Check> jammingChecks;
    public final List<Check> spoofingChecks;

    public IntegrityReport(Level jamming, Level spoofing, List<Check> jammingChecks, List<Check> spoofingChecks) {
        this.jamming = jamming;
        this.spoofing = spoofing;
        this.jammingChecks = Collections.unmodifiableList(jammingChecks);
        this.spoofingChecks = Collections.unmodifiableList(spoofingChecks);
    }

    public Level worst() {
        return jamming.compareTo(spoofing) >= 0 ? jamming : spoofing;
    }
}

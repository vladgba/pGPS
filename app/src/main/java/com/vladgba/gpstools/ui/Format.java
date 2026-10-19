package com.vladgba.gpstools.ui;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/** Locale-independent formatting so values can be copied and compared reliably. */
public final class Format {

    public static final String NONE = "—";

    private static final String[] COMPASS = {
            "N", "NNE", "NE", "ENE", "E", "ESE", "SE", "SSE",
            "S", "SSW", "SW", "WSW", "W", "WNW", "NW", "NNW"};

    private static final SimpleDateFormat UTC;

    static {
        UTC = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.ROOT);
        UTC.setTimeZone(TimeZone.getTimeZone("UTC"));
    }

    private Format() {
    }

    public static String degrees(double value) {
        return String.format(Locale.ROOT, "%.7f°", value);
    }

    /** Degrees, minutes, seconds with hemisphere, e.g. 48°51'29.60"N. */
    public static String dms(double value, boolean latitude) {
        char hemisphere = latitude ? (value >= 0 ? 'N' : 'S') : (value >= 0 ? 'E' : 'W');
        // Round once on hundredths of a second so 59.999" never prints as 60.00".
        long hundredths = Math.round(Math.abs(value) * 360_000d);
        long deg = hundredths / 360_000;
        long rest = hundredths % 360_000;
        long min = rest / 6_000;
        rest %= 6_000;
        return String.format(Locale.ROOT, "%d°%02d'%05.2f\"%c", deg, min, rest / 100d, hemisphere);
    }

    /** Degrees and decimal minutes, e.g. 48°51.4933'N (common on marine/aviation devices). */
    public static String ddm(double value, boolean latitude) {
        char hemisphere = latitude ? (value >= 0 ? 'N' : 'S') : (value >= 0 ? 'E' : 'W');
        long tenThousandths = Math.round(Math.abs(value) * 600_000d);
        long deg = tenThousandths / 600_000;
        double minutes = (tenThousandths % 600_000) / 10_000d;
        return String.format(Locale.ROOT, "%d°%07.4f'%c", deg, minutes, hemisphere);
    }

    public static String coordinates(double lat, double lon) {
        return String.format(Locale.ROOT, "%.7f, %.7f", lat, lon);
    }

    public static String meters(double value) {
        return Double.isNaN(value) ? NONE : String.format(Locale.ROOT, "%.1f m", value);
    }

    public static String accuracy(double value) {
        return String.format(Locale.ROOT, "± %.1f m", value);
    }

    public static String speed(double metersPerSecond) {
        return String.format(Locale.ROOT, "%.2f m/s · %.1f km/h", metersPerSecond, metersPerSecond * 3.6);
    }

    public static String speedAccuracy(double metersPerSecond) {
        return String.format(Locale.ROOT, "± %.2f m/s", metersPerSecond);
    }

    public static String bearing(double deg) {
        int index = (int) Math.round(((deg % 360) + 360) % 360 / 22.5) % COMPASS.length;
        return String.format(Locale.ROOT, "%.1f° %s", deg, COMPASS[index]);
    }

    public static String angleAccuracy(double deg) {
        return String.format(Locale.ROOT, "± %.1f°", deg);
    }

    public static String dop(double value) {
        return Double.isNaN(value) ? NONE : String.format(Locale.ROOT, "%.2f", value);
    }

    public static String seconds(long ms) {
        return String.format(Locale.ROOT, "%.1f s", ms / 1000d);
    }

    public static String duration(long ms) {
        long s = ms / 1000;
        if (s < 60) return s + " s";
        if (s < 3600) return String.format(Locale.ROOT, "%d min %02d s", s / 60, s % 60);
        return String.format(Locale.ROOT, "%d h %02d min", s / 3600, (s % 3600) / 60);
    }

    public static String utc(long epochMs) {
        return UTC.format(new Date(epochMs));
    }

    public static String distance(double meters) {
        if (meters >= 10_000) return String.format(Locale.ROOT, "%.0f km", meters / 1000);
        if (meters >= 1000) return String.format(Locale.ROOT, "%.2f km", meters / 1000);
        return String.format(Locale.ROOT, "%.0f m", meters);
    }

    public static String fileSize(long bytes) {
        if (bytes >= 1024 * 1024) return String.format(Locale.ROOT, "%.1f MB", bytes / 1048576.0);
        if (bytes >= 1024) return String.format(Locale.ROOT, "%.0f KB", bytes / 1024.0);
        return bytes + " B";
    }

    public static String cn0(float dbHz) {
        return dbHz > 0 ? String.format(Locale.ROOT, "%.1f dB-Hz", dbHz) : NONE;
    }
}

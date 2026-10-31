package x.vladgba.gps.gnss;

import android.location.Location;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * Last 30 minutes of fix quality (accuracy, DOP, satellite counts, signal strength) and of
 * each satellite's C/N0 and elevation, sampled once per satellite status epoch (~1 Hz).
 */
public final class QualityHistory {

    public static final long WINDOW_MS = 30 * 60_000L;
    private static final int CAPACITY = 1900; // 30 min at 1 Hz plus slack
    private static final int TOP_N = 4;

    public final RingSeries horizontalAccuracy = new RingSeries(CAPACITY);
    public final RingSeries verticalAccuracy = new RingSeries(CAPACITY);
    public final RingSeries hdop = new RingSeries(CAPACITY);
    public final RingSeries pdop = new RingSeries(CAPACITY);
    public final RingSeries satellitesUsed = new RingSeries(CAPACITY);
    public final RingSeries satellitesInView = new RingSeries(CAPACITY);
    public final RingSeries topCn0 = new RingSeries(CAPACITY);
    public final RingSeries meanCn0 = new RingSeries(CAPACITY);

    private final Map<Long, SatelliteHistory> satellites = new HashMap<>();

    /** C/N0 and elevation of one satellite (strongest of its signals). */
    public static final class SatelliteHistory {
        public final RingSeries cn0 = new RingSeries(CAPACITY);
        public final RingSeries elevation = new RingSeries(CAPACITY);
    }

    public void onEpoch(long nowMs, GnssMonitor m) {
        Location loc = m.hasFreshFix() ? m.getLocation() : null;
        horizontalAccuracy.add(nowMs, loc != null && loc.hasAccuracy() ? loc.getAccuracy() : Float.NaN);
        float vAcc = Float.NaN;
        if (loc != null && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O
                && loc.hasVerticalAccuracy()) {
            vAcc = loc.getVerticalAccuracyMeters();
        }
        verticalAccuracy.add(nowMs, vAcc);
        NmeaFix nmea = m.getNmeaFix();
        boolean nmeaFresh = m.isNmeaFresh();
        hdop.add(nowMs, nmeaFresh ? (float) nmea.getHdop() : Float.NaN);
        pdop.add(nowMs, nmeaFresh ? (float) nmea.getPdop() : Float.NaN);
        satellitesUsed.add(nowMs, m.getSatellitesUsed());
        satellitesInView.add(nowMs, m.getSatellitesInView());

        Map<Long, float[]> strongest = new HashMap<>();
        float[] cn0s = new float[m.getSatellites().size()];
        int n = 0;
        double sum = 0;
        for (SatelliteInfo s : m.getSatellites()) {
            if (s.cn0 > 0) {
                cn0s[n++] = s.cn0;
                sum += s.cn0;
            }
            float[] best = strongest.get(s.key());
            if (best == null || s.cn0 > best[0]) strongest.put(s.key(), new float[]{s.cn0, s.elevation});
        }
        java.util.Arrays.sort(cn0s, 0, n);
        double top = 0;
        int k = Math.min(TOP_N, n);
        for (int i = 0; i < k; i++) top += cn0s[n - 1 - i];
        topCn0.add(nowMs, k == 0 ? Float.NaN : (float) (top / k));
        meanCn0.add(nowMs, n == 0 ? Float.NaN : (float) (sum / n));

        for (Map.Entry<Long, float[]> e : strongest.entrySet()) {
            SatelliteHistory h = satellites.get(e.getKey());
            if (h == null) {
                h = new SatelliteHistory();
                satellites.put(e.getKey(), h);
            }
            h.cn0.add(nowMs, e.getValue()[0]);
            h.elevation.add(nowMs, e.getValue()[1]);
        }
        // Forget satellites not seen for a whole window.
        for (Iterator<SatelliteHistory> it = satellites.values().iterator(); it.hasNext(); ) {
            if (nowMs - it.next().cn0.lastTime() > WINDOW_MS) it.remove();
        }
    }

    /** History of one satellite (see {@link SatelliteInfo#key()}), or null if never seen. */
    public SatelliteHistory satellite(long key) {
        return satellites.get(key);
    }
}

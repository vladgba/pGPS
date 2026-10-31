package x.vladgba.gps.orbit;

import android.annotation.SuppressLint;
import android.content.Context;
import android.location.GnssStatus;
import android.os.Handler;
import android.os.Looper;

import x.vladgba.gps.gnss.SatelliteInfo;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * GNSS orbit catalog from CelesTrak TLEs: download + cache, constellation/PRN identification,
 * current look angles, rise/set events and visibility forecasts.
 *
 * <p>GPS, BeiDou and QZSS TLE names carry the PRN. GLONASS ("COSMOS nnnn") and Galileo
 * ("GSATnnnn") do not, so those are matched to the satellites the receiver actually tracks by
 * nearest sky position (same constellation, within a few degrees). Main thread only, except the
 * read-only geometry methods, which background threads may call.
 */
@SuppressLint("InlinedApi") // CONSTELLATION_* are compile-time constants
public final class OrbitCatalog {

    public interface Callback {
        void onCatalogChanged(OrbitCatalog catalog, String error);
    }

    public static final String SOURCE_URL = "https://celestrak.org/NORAD/elements/gp.php?GROUP=gnss&FORMAT=tle";
    /** TLEs older than this are refreshed automatically when the catalog is used. */
    public static final long MAX_AGE_MS = 3 * 86_400_000L;
    private static final double MATCH_DEG = 4.0;
    private static final double UNMATCH_DEG = 8.0;
    private static final Pattern PRN = Pattern.compile("PRN\\s*0*(\\d+)");
    private static final Pattern BEIDOU = Pattern.compile("\\(C0*(\\d+)\\)");

    /** One catalog satellite. {@link #svid} is -1 until identified. */
    public static final class Satellite {
        public final Tle tle;
        public final int constellation;
        public final OrbitPropagator propagator;
        final boolean svidFromName;
        public int svid;

        Satellite(Tle tle, int constellation, int svid) {
            this.tle = tle;
            this.constellation = constellation;
            this.svid = svid;
            this.svidFromName = svid > 0;
            this.propagator = new OrbitPropagator(tle);
        }

        /** Same key as {@link SatelliteInfo#key()} once identified, else a NORAD-based key. */
        public long key() {
            return svid > 0 ? ((long) constellation << 32) | (svid & 0xFFFFFFFFL) : -tle.noradId;
        }
    }

    /** Look angles of one satellite at one time. */
    public static final class Position {
        public final Satellite satellite;
        public final float elevation;
        public final float azimuth;

        Position(Satellite satellite, float elevation, float azimuth) {
            this.satellite = satellite;
            this.elevation = elevation;
            this.azimuth = azimuth;
        }
    }

    /** Rise or set across the elevation mask. */
    public static final class Pass {
        public final Satellite satellite;
        public final long timeMs;
        public final boolean rising;
        public final float azimuth;

        Pass(Satellite satellite, long timeMs, boolean rising, float azimuth) {
            this.satellite = satellite;
            this.timeMs = timeMs;
            this.rising = rising;
            this.azimuth = azimuth;
        }
    }

    private static OrbitCatalog instance;

    public static synchronized OrbitCatalog get(Context context) {
        if (instance == null) instance = new OrbitCatalog(context.getApplicationContext());
        return instance;
    }

    private final File cacheFile;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<Callback> callbacks = new ArrayList<>();
    private List<Satellite> satellites = Collections.emptyList();
    private long fetchedAtMs;
    private boolean loading;
    private String lastError;

    private OrbitCatalog(Context context) {
        File dir = new File(context.getFilesDir(), "orbits");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        cacheFile = new File(dir, "gnss.tle");
        if (cacheFile.exists()) {
            try {
                setTles(Tle.parseAll(read(new FileInputStream(cacheFile))));
                fetchedAtMs = cacheFile.lastModified();
            } catch (IOException e) {
                lastError = e.getMessage();
            }
        }
    }

    public void addCallback(Callback c) {
        if (!callbacks.contains(c)) callbacks.add(c);
    }

    public void removeCallback(Callback c) {
        callbacks.remove(c);
    }

    public List<Satellite> getSatellites() {
        return satellites;
    }

    public boolean isEmpty() {
        return satellites.isEmpty();
    }

    public long getFetchedAtMs() {
        return fetchedAtMs;
    }

    public boolean isLoading() {
        return loading;
    }

    public String getLastError() {
        return lastError;
    }

    /** Downloads when there is no data yet or it is older than {@link #MAX_AGE_MS}. */
    public void refreshIfStale() {
        if (satellites.isEmpty() || System.currentTimeMillis() - fetchedAtMs > MAX_AGE_MS) refresh();
    }

    /** Downloads the catalog in the background; callbacks fire on the main thread. */
    public void refresh() {
        if (loading) return;
        loading = true;
        notifyChanged();
        new Thread(() -> {
            String error = null;
            List<Tle> tles = null;
            try {
                HttpURLConnection c = (HttpURLConnection) new URL(SOURCE_URL).openConnection();
                c.setConnectTimeout(15_000);
                c.setReadTimeout(20_000);
                c.setRequestProperty("User-Agent", "GPS Tools Android");
                int code = c.getResponseCode();
                if (code != HttpURLConnection.HTTP_OK) throw new IOException("HTTP " + code);
                String text = read(c.getInputStream());
                tles = Tle.parseAll(text);
                if (tles.isEmpty()) throw new IOException("No orbits in the response");
                try (FileOutputStream out = new FileOutputStream(cacheFile)) {
                    out.write(text.getBytes(StandardCharsets.US_ASCII));
                }
            } catch (IOException | RuntimeException e) {
                error = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            }
            final List<Tle> result = tles;
            final String err = error;
            main.post(() -> {
                loading = false;
                lastError = err;
                if (result != null) {
                    setTles(result);
                    fetchedAtMs = System.currentTimeMillis();
                }
                notifyChanged();
            });
        }, "tle-download").start();
    }

    private void notifyChanged() {
        for (Callback c : new ArrayList<>(callbacks)) c.onCatalogChanged(this, lastError);
    }

    private void setTles(List<Tle> tles) {
        List<Satellite> list = new ArrayList<>();
        for (Tle t : tles) {
            int constellation = constellationOf(t.name);
            if (constellation == GnssStatus.CONSTELLATION_UNKNOWN) continue;
            list.add(new Satellite(t, constellation, svidOf(t.name, constellation)));
        }
        satellites = Collections.unmodifiableList(list);
    }

    static int constellationOf(String name) {
        String n = name.toUpperCase(java.util.Locale.ROOT);
        if (n.startsWith("GPS") || n.contains("NAVSTAR")) return GnssStatus.CONSTELLATION_GPS;
        if (n.startsWith("COSMOS") || n.contains("GLONASS")) return GnssStatus.CONSTELLATION_GLONASS;
        if (n.startsWith("GSAT") || n.contains("GALILEO")) return GnssStatus.CONSTELLATION_GALILEO;
        if (n.startsWith("BEIDOU")) return GnssStatus.CONSTELLATION_BEIDOU;
        if (n.startsWith("QZS")) return GnssStatus.CONSTELLATION_QZSS;
        if (n.startsWith("IRNSS") || n.startsWith("NVS")) return GnssStatus.CONSTELLATION_IRNSS;
        return GnssStatus.CONSTELLATION_UNKNOWN;
    }

    static int svidOf(String name, int constellation) {
        Matcher m;
        switch (constellation) {
            case GnssStatus.CONSTELLATION_GPS:
            case GnssStatus.CONSTELLATION_QZSS:
                m = PRN.matcher(name);
                return m.find() ? Integer.parseInt(m.group(1)) : -1;
            case GnssStatus.CONSTELLATION_BEIDOU:
                m = BEIDOU.matcher(name);
                return m.find() ? Integer.parseInt(m.group(1)) : -1;
            default:
                return -1;
        }
    }

    // ---- Geometry -------------------------------------------------------------------------------

    /** Look angles of every satellite at {@code timeMs}, below the horizon included. */
    public List<Position> positions(long timeMs, double lat, double lon, double altM) {
        double[] obs = new double[3];
        OrbitPropagator.observerEcef(lat, lon, altM, obs);
        double[] sat = new double[3];
        float[] look = new float[2];
        List<Position> result = new ArrayList<>(satellites.size());
        for (Satellite s : satellites) {
            s.propagator.positionEcef(timeMs, sat);
            OrbitPropagator.lookAngles(sat, obs, lat, lon, look);
            result.add(new Position(s, look[0], look[1]));
        }
        return result;
    }

    /**
     * Identifies GLONASS/Galileo (and unnamed) satellites by matching predicted positions to the
     * tracked ones of the same constellation. Assignments persist until they drift apart.
     */
    public void matchObserved(List<Position> predicted, List<SatelliteInfo> observed) {
        Map<Long, SatelliteInfo> tracked = new HashMap<>();
        for (SatelliteInfo s : observed) {
            if (s.hasSkyPosition()) tracked.put(s.key(), s);
        }
        Set<Long> claimed = new HashSet<>();
        for (Position p : predicted) {
            if (p.satellite.svid > 0) claimed.add(p.satellite.key());
        }
        for (Position p : predicted) {
            Satellite s = p.satellite;
            if (s.svidFromName) continue;
            if (s.svid > 0) {
                SatelliteInfo current = tracked.get(s.key());
                if (current != null && angle(p.elevation, p.azimuth, current.elevation, current.azimuth) > UNMATCH_DEG) {
                    claimed.remove(s.key());
                    s.svid = -1;
                }
                continue;
            }
            if (p.elevation < 0) continue;
            SatelliteInfo best = null;
            double bestAngle = MATCH_DEG;
            for (SatelliteInfo o : tracked.values()) {
                if (o.constellation != s.constellation || claimed.contains(o.key())) continue;
                double a = angle(p.elevation, p.azimuth, o.elevation, o.azimuth);
                if (a < bestAngle) {
                    bestAngle = a;
                    best = o;
                }
            }
            if (best != null) {
                s.svid = best.svid;
                claimed.add(best.key());
            }
        }
    }

    /** Elevation/azimuth samples of one satellite between two times (for orbit tracks). */
    public List<float[]> track(Satellite s, long fromMs, long toMs, long stepMs, double lat, double lon, double altM) {
        double[] obs = new double[3];
        OrbitPropagator.observerEcef(lat, lon, altM, obs);
        double[] sat = new double[3];
        List<float[]> result = new ArrayList<>();
        for (long t = fromMs; t <= toMs; t += stepMs) {
            float[] look = new float[2];
            s.propagator.positionEcef(t, sat);
            OrbitPropagator.lookAngles(sat, obs, lat, lon, look);
            result.add(look);
        }
        return result;
    }

    /** Rises and sets across {@code maskDeg} between now and {@code horizonMs}, time-ordered. */
    public List<Pass> passes(long fromMs, long durationMs, float maskDeg, double lat, double lon, double altM) {
        final long step = 60_000;
        double[] obs = new double[3];
        OrbitPropagator.observerEcef(lat, lon, altM, obs);
        double[] sat = new double[3];
        float[] look = new float[2];
        List<Pass> result = new ArrayList<>();
        for (Satellite s : satellites) {
            s.propagator.positionEcef(fromMs, sat);
            OrbitPropagator.lookAngles(sat, obs, lat, lon, look);
            float prevEl = look[0];
            for (long t = fromMs + step; t <= fromMs + durationMs; t += step) {
                s.propagator.positionEcef(t, sat);
                OrbitPropagator.lookAngles(sat, obs, lat, lon, look);
                float el = look[0];
                if ((prevEl < maskDeg) != (el < maskDeg)) {
                    double frac = (maskDeg - prevEl) / (el - prevEl);
                    result.add(new Pass(s, (long) (t - step + frac * step), el >= maskDeg, look[1]));
                }
                prevEl = el;
            }
        }
        Collections.sort(result, (a, b) -> Long.compare(a.timeMs, b.timeMs));
        return result;
    }

    /**
     * Number of satellites above the mask per constellation (index = constellation type) at
     * {@code steps} times spaced {@code stepMs} apart from {@code fromMs}.
     */
    public int[][] visibilityForecast(long fromMs, long stepMs, int steps, float maskDeg,
                                      double lat, double lon, double altM) {
        int[][] counts = new int[steps][8];
        double[] obs = new double[3];
        OrbitPropagator.observerEcef(lat, lon, altM, obs);
        double[] sat = new double[3];
        float[] look = new float[2];
        for (int i = 0; i < steps; i++) {
            long t = fromMs + i * stepMs;
            for (Satellite s : satellites) {
                s.propagator.positionEcef(t, sat);
                OrbitPropagator.lookAngles(sat, obs, lat, lon, look);
                if (look[0] >= maskDeg && s.constellation < 8) counts[i][s.constellation]++;
            }
        }
        return counts;
    }

    static double angle(double el1, double az1, double el2, double az2) {
        double e1 = Math.toRadians(el1);
        double e2 = Math.toRadians(el2);
        double cos = Math.sin(e1) * Math.sin(e2) + Math.cos(e1) * Math.cos(e2) * Math.cos(Math.toRadians(az1 - az2));
        return Math.toDegrees(Math.acos(Math.max(-1, Math.min(1, cos))));
    }

    private static String read(InputStream in) throws IOException {
        try (InputStream is = in) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[16 * 1024];
            int n;
            while ((n = is.read(buffer)) > 0) out.write(buffer, 0, n);
            return out.toString("US-ASCII");
        }
    }
}

package x.vladgba.gps.nav;

import android.content.Context;
import android.location.Location;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.Writer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Breadcrumb trail for backtracking. A point is kept only when the fix is usable and the user
 * has moved since the last point, so standing still (or a receiver's static hold) adds nothing.
 * Points are appended to a file as they come, so the trail survives the app being closed.
 * Main thread only.
 */
public final class Trail {

    public interface Listener {
        void onTrailChanged();
    }

    /** One breadcrumb: WGS84 degrees, ellipsoidal metres (NaN if unknown), UTC ms. */
    public static final class Point {
        public final double latitude;
        public final double longitude;
        public final double altitude;
        public final long timeMs;

        Point(double latitude, double longitude, double altitude, long timeMs) {
            this.latitude = latitude;
            this.longitude = longitude;
            this.altitude = altitude;
            this.timeMs = timeMs;
        }
    }

    /** Fixes less accurate than this are ignored. */
    static final float MAX_ACCURACY_M = 25f;
    /** Minimum movement between breadcrumbs. */
    static final double MIN_STEP_M = 5;

    private static Trail instance;

    public static synchronized Trail get(Context context) {
        if (instance == null) instance = new Trail(context.getApplicationContext());
        return instance;
    }

    private final File file;
    private final List<Point> points = new ArrayList<>();
    private final List<Listener> listeners = new ArrayList<>();
    private double lengthM;

    private Trail(Context context) {
        file = new File(context.getFilesDir(), "trail.csv");
        load();
    }

    public void addListener(Listener l) {
        if (!listeners.contains(l)) listeners.add(l);
    }

    public void removeListener(Listener l) {
        listeners.remove(l);
    }

    public List<Point> getPoints() {
        return Collections.unmodifiableList(points);
    }

    public boolean isEmpty() {
        return points.isEmpty();
    }

    /** Length along the trail in metres. */
    public double getLengthM() {
        return lengthM;
    }

    /** Adds a breadcrumb if the fix is accurate enough and far enough from the last one. */
    public boolean offer(Location loc) {
        float accuracy = loc.hasAccuracy() ? loc.getAccuracy() : Float.POSITIVE_INFINITY;
        if (accuracy > MAX_ACCURACY_M) return false;
        if (!points.isEmpty()) {
            Point last = points.get(points.size() - 1);
            double moved = Waypoint.distance(last.latitude, last.longitude, loc.getLatitude(), loc.getLongitude());
            // Movement smaller than the fix's own noise is not movement.
            if (moved < Math.max(MIN_STEP_M, accuracy * 0.6)) return false;
            lengthM += moved;
        }
        Point p = new Point(loc.getLatitude(), loc.getLongitude(),
                loc.hasAltitude() ? loc.getAltitude() : Double.NaN, loc.getTime());
        points.add(p);
        append(p);
        notifyChanged();
        return true;
    }

    public void clear() {
        points.clear();
        lengthM = 0;
        //noinspection ResultOfMethodCallIgnored
        file.delete();
        notifyChanged();
    }

    private void notifyChanged() {
        for (Listener l : new ArrayList<>(listeners)) l.onTrailChanged();
    }

    private void append(Point p) {
        try (Writer w = new FileWriter(file, true)) {
            w.write(String.format(Locale.ROOT, "%d,%.8f,%.8f,%s%n", p.timeMs, p.latitude, p.longitude,
                    Double.isNaN(p.altitude) ? "" : String.format(Locale.ROOT, "%.1f", p.altitude)));
        } catch (IOException e) {
            // Keep the in-memory trail; only persistence is lost.
        }
    }

    private void load() {
        if (!file.exists()) return;
        try (BufferedReader r = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = r.readLine()) != null) {
                String[] f = line.split(",", -1);
                if (f.length < 4) continue;
                try {
                    Point p = new Point(Double.parseDouble(f[1]), Double.parseDouble(f[2]),
                            f[3].isEmpty() ? Double.NaN : Double.parseDouble(f[3]), Long.parseLong(f[0]));
                    if (!points.isEmpty()) {
                        Point last = points.get(points.size() - 1);
                        lengthM += Waypoint.distance(last.latitude, last.longitude, p.latitude, p.longitude);
                    }
                    points.add(p);
                } catch (NumberFormatException ignored) {
                    // skip a damaged line (e.g. power loss mid-write)
                }
            }
        } catch (IOException e) {
            // start with what was read
        }
    }
}

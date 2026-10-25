package com.vladgba.gpstools.spoof;

import android.annotation.SuppressLint;
import android.app.AppOpsManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.location.Location;
import android.location.LocationManager;
import android.location.provider.ProviderProperties;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Process;
import android.os.SystemClock;

import com.vladgba.gpstools.MainActivity;
import com.vladgba.gpstools.R;
import com.vladgba.gpstools.ui.Format;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Feeds a fake position to the system location providers through the test-provider API, which
 * only works once this app is chosen in Developer options → "Select mock location app".
 *
 * <p>A foreground "location" service so the position keeps being pushed (1 Hz) with the app in
 * the background: providers that stop receiving updates fall back to stale data. The position
 * can stand still or move at a constant speed and heading, with optional GPS-like jitter.
 */
public final class SpoofService extends Service {

    public interface Listener {
        void onSpoofChanged();
    }

    /** What to spoof. Immutable; a new one replaces the running one in place. */
    public static final class Config {
        public final double latitude;
        public final double longitude;
        public final double altitude;
        public final float accuracyM;
        public final float speedMps;
        public final float bearingDeg;
        public final boolean jitter;
        public final boolean gps;
        public final boolean network;
        public final boolean fused;

        public Config(double latitude, double longitude, double altitude, float accuracyM, float speedMps,
                      float bearingDeg, boolean jitter, boolean gps, boolean network, boolean fused) {
            this.latitude = latitude;
            this.longitude = longitude;
            this.altitude = altitude;
            this.accuracyM = accuracyM;
            this.speedMps = speedMps;
            this.bearingDeg = bearingDeg;
            this.jitter = jitter;
            this.gps = gps;
            this.network = network;
            this.fused = fused;
        }
    }

    private static final String ACTION_START = "com.vladgba.gpstools.spoof.START";
    private static final String ACTION_STOP = "com.vladgba.gpstools.spoof.STOP";
    private static final String CHANNEL = "spoof";
    private static final int NOTIFICATION_ID = 4;
    private static final long TICK_MS = 1000;
    private static final double EARTH_RADIUS_M = 6_371_008.8;
    private static final String FUSED = "fused"; // LocationManager.FUSED_PROVIDER (API 31)

    private static final String EXTRA_LAT = "lat";
    private static final String EXTRA_LON = "lon";
    private static final String EXTRA_ALT = "alt";
    private static final String EXTRA_ACCURACY = "accuracy";
    private static final String EXTRA_SPEED = "speed";
    private static final String EXTRA_BEARING = "bearing";
    private static final String EXTRA_JITTER = "jitter";
    private static final String EXTRA_GPS = "gps";
    private static final String EXTRA_NETWORK = "network";
    private static final String EXTRA_FUSED = "fused";

    private static Config config;
    private static boolean running;
    private static double currentLat = Double.NaN;
    private static double currentLon = Double.NaN;
    private static String lastError;
    private static final List<String> activeProviders = new ArrayList<>();
    private static final List<Listener> listeners = new ArrayList<>();

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Random random = new Random();
    private LocationManager locationManager;
    private long lastTickElapsed;
    private double jitterE;
    private double jitterN;
    private int ticksSinceNotification;
    private final Runnable tick = this::tick;

    // ---- Static API for the UI ----------------------------------------------------------------

    /** Starts spoofing, or applies a new configuration if already running. */
    public static void start(Context context, Config c) {
        // The config travels in the intent: two quick starts must not share one static slot.
        Intent i = new Intent(context, SpoofService.class).setAction(ACTION_START)
                .putExtra(EXTRA_LAT, c.latitude)
                .putExtra(EXTRA_LON, c.longitude)
                .putExtra(EXTRA_ALT, c.altitude)
                .putExtra(EXTRA_ACCURACY, c.accuracyM)
                .putExtra(EXTRA_SPEED, c.speedMps)
                .putExtra(EXTRA_BEARING, c.bearingDeg)
                .putExtra(EXTRA_JITTER, c.jitter)
                .putExtra(EXTRA_GPS, c.gps)
                .putExtra(EXTRA_NETWORK, c.network)
                .putExtra(EXTRA_FUSED, c.fused);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(i);
        else context.startService(i);
    }

    public static void stop(Context context) {
        if (!running) return;
        context.startService(new Intent(context, SpoofService.class).setAction(ACTION_STOP));
    }

    public static boolean isRunning() {
        return running;
    }

    public static Config getConfig() {
        return config;
    }

    /** Position being pushed now (moves when a speed is set), NaN when stopped. */
    public static double getCurrentLatitude() {
        return currentLat;
    }

    public static double getCurrentLongitude() {
        return currentLon;
    }

    public static List<String> getActiveProviders() {
        return new ArrayList<>(activeProviders);
    }

    /** Why the last start failed or what was skipped, or null. */
    public static String getLastError() {
        return lastError;
    }

    public static void addListener(Listener l) {
        if (!listeners.contains(l)) listeners.add(l);
    }

    public static void removeListener(Listener l) {
        listeners.remove(l);
    }

    private static void notifyListeners() {
        for (Listener l : new ArrayList<>(listeners)) l.onSpoofChanged();
    }

    /** Whether this app is the one selected in Developer options → "Select mock location app". */
    @SuppressWarnings("deprecation")
    public static boolean isMockAppSelected(Context context) {
        AppOpsManager ops = context.getSystemService(AppOpsManager.class);
        if (ops == null) return false;
        try {
            int mode = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                    ? ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_MOCK_LOCATION, Process.myUid(), context.getPackageName())
                    : ops.checkOpNoThrow(AppOpsManager.OPSTR_MOCK_LOCATION, Process.myUid(), context.getPackageName());
            return mode == AppOpsManager.MODE_ALLOWED;
        } catch (RuntimeException e) {
            return false;
        }
    }

    // ---- Service ------------------------------------------------------------------------------

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        locationManager = getSystemService(LocationManager.class);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : null;
        if (ACTION_START.equals(action)) {
            // Promote first: a startForegroundService() must be answered even when it fails.
            goForeground();
            Config next = readConfig(intent);
            if (next == null || !apply(next)) {
                shutDown();
                return START_NOT_STICKY;
            }
            notifyListeners();
        } else {
            shutDown();
        }
        return START_NOT_STICKY;
    }

    private static Config readConfig(Intent i) {
        if (!i.hasExtra(EXTRA_LAT) || !i.hasExtra(EXTRA_LON)) return null;
        double lat = i.getDoubleExtra(EXTRA_LAT, Double.NaN);
        double lon = i.getDoubleExtra(EXTRA_LON, Double.NaN);
        if (!(Math.abs(lat) <= 90) || !(Math.abs(lon) <= 180)) return null;
        return new Config(lat, lon, i.getDoubleExtra(EXTRA_ALT, 0),
                i.getFloatExtra(EXTRA_ACCURACY, 5), i.getFloatExtra(EXTRA_SPEED, 0),
                i.getFloatExtra(EXTRA_BEARING, 0), i.getBooleanExtra(EXTRA_JITTER, false),
                i.getBooleanExtra(EXTRA_GPS, false), i.getBooleanExtra(EXTRA_NETWORK, false),
                i.getBooleanExtra(EXTRA_FUSED, false));
    }

    /** Installs the test providers for {@code next}; false if none could be installed. */
    private boolean apply(Config next) {
        lastError = null;
        if (!isMockAppSelected(this)) {
            lastError = getString(R.string.spoof_err_not_selected);
            return false;
        }
        List<String> wanted = new ArrayList<>();
        if (next.gps) wanted.add(LocationManager.GPS_PROVIDER);
        if (next.network) wanted.add(LocationManager.NETWORK_PROVIDER);
        if (next.fused && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) wanted.add(FUSED);
        // Drop providers no longer selected so the real ones come back.
        for (String p : new ArrayList<>(activeProviders)) {
            if (!wanted.contains(p)) removeProvider(p);
        }
        List<String> failed = new ArrayList<>();
        for (String p : wanted) {
            if (activeProviders.contains(p)) continue;
            if (addProvider(p)) activeProviders.add(p);
            else failed.add(p);
        }
        if (!failed.isEmpty()) lastError = getString(R.string.spoof_err_providers, String.join(", ", failed));
        if (activeProviders.isEmpty()) {
            if (lastError == null) lastError = getString(R.string.spoof_err_no_provider);
            return false;
        }
        boolean moved = config == null || config.latitude != next.latitude || config.longitude != next.longitude;
        config = next;
        if (moved || Double.isNaN(currentLat)) {
            currentLat = next.latitude;
            currentLon = next.longitude;
        }
        running = true;
        lastTickElapsed = SystemClock.elapsedRealtime();
        handler.removeCallbacks(tick);
        tick();
        updateNotification();
        return true;
    }

    // The ProviderProperties ints are compile-time constants equal to the old Criteria values.
    @SuppressLint("InlinedApi")
    @SuppressWarnings("deprecation") // the boolean overload is the only one below API 31
    private boolean addProvider(String name) {
        try {
            // A provider left over by a killed process would make addTestProvider fail.
            locationManager.removeTestProvider(name);
        } catch (SecurityException | IllegalArgumentException | IllegalStateException ignored) {
            // Nothing to remove.
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                ProviderProperties props = new ProviderProperties.Builder()
                        .setHasAltitudeSupport(true)
                        .setHasSpeedSupport(true)
                        .setHasBearingSupport(true)
                        .setHasSatelliteRequirement(LocationManager.GPS_PROVIDER.equals(name))
                        .setPowerUsage(ProviderProperties.POWER_USAGE_HIGH)
                        .setAccuracy(ProviderProperties.ACCURACY_FINE)
                        .build();
                locationManager.addTestProvider(name, props);
            } else {
                locationManager.addTestProvider(name, false, LocationManager.GPS_PROVIDER.equals(name), false,
                        false, true, true, true, ProviderProperties.POWER_USAGE_HIGH, ProviderProperties.ACCURACY_FINE);
            }
            locationManager.setTestProviderEnabled(name, true);
            return true;
        } catch (SecurityException | IllegalArgumentException | IllegalStateException e) {
            return false;
        }
    }

    private void removeProvider(String name) {
        activeProviders.remove(name);
        try {
            locationManager.removeTestProvider(name);
        } catch (SecurityException | IllegalArgumentException | IllegalStateException ignored) {
            // Already gone (e.g. mock app deselected in Developer options).
        }
    }

    private void tick() {
        if (!running || config == null) return;
        long now = SystemClock.elapsedRealtime();
        double dt = (now - lastTickElapsed) / 1000.0;
        lastTickElapsed = now;
        if (config.speedMps > 0 && dt > 0) advance(config.speedMps * dt, config.bearingDeg);

        double lat = currentLat;
        double lon = currentLon;
        if (config.jitter) {
            // Random walk around the true point, bounded by the accuracy: looks like a real fix.
            double sigma = Math.max(0.5, config.accuracyM / 3.0);
            jitterE = jitterE * 0.8 + random.nextGaussian() * sigma * 0.45;
            jitterN = jitterN * 0.8 + random.nextGaussian() * sigma * 0.45;
            lat += Math.toDegrees(jitterN / EARTH_RADIUS_M);
            lon += Math.toDegrees(jitterE / (EARTH_RADIUS_M * Math.cos(Math.toRadians(lat))));
        }
        boolean lostAccess = false;
        for (String p : new ArrayList<>(activeProviders)) {
            try {
                locationManager.setTestProviderLocation(p, buildLocation(p, lat, lon));
            } catch (SecurityException | IllegalArgumentException e) {
                lostAccess = true; // deselected as mock app, or the provider was removed
            }
        }
        if (lostAccess && !isMockAppSelected(this)) {
            lastError = getString(R.string.spoof_err_not_selected);
            shutDown();
            return;
        }
        if (++ticksSinceNotification >= 5) {
            ticksSinceNotification = 0;
            updateNotification();
        }
        notifyListeners();
        handler.postDelayed(tick, TICK_MS);
    }

    /** Moves the current point along a great circle. */
    private void advance(double distanceM, double bearingDeg) {
        double d = distanceM / EARTH_RADIUS_M;
        double b = Math.toRadians(bearingDeg);
        double lat1 = Math.toRadians(currentLat);
        double lon1 = Math.toRadians(currentLon);
        double lat2 = Math.asin(Math.sin(lat1) * Math.cos(d) + Math.cos(lat1) * Math.sin(d) * Math.cos(b));
        double lon2 = lon1 + Math.atan2(Math.sin(b) * Math.sin(d) * Math.cos(lat1),
                Math.cos(d) - Math.sin(lat1) * Math.sin(lat2));
        currentLat = Math.toDegrees(lat2);
        currentLon = ((Math.toDegrees(lon2) + 540) % 360) - 180;
    }

    private Location buildLocation(String provider, double lat, double lon) {
        Location l = new Location(provider);
        l.setLatitude(lat);
        l.setLongitude(lon);
        l.setAltitude(config.altitude);
        // Network fixes are never as good as GPS ones; keep them plausible.
        float accuracy = LocationManager.NETWORK_PROVIDER.equals(provider)
                ? Math.max(config.accuracyM, 15f) : config.accuracyM;
        if (config.jitter) accuracy *= (float) (0.85 + random.nextDouble() * 0.3);
        l.setAccuracy(accuracy);
        l.setSpeed(config.speedMps);
        l.setBearing(config.bearingDeg);
        l.setTime(System.currentTimeMillis());
        l.setElapsedRealtimeNanos(SystemClock.elapsedRealtimeNanos());
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            l.setVerticalAccuracyMeters(accuracy * 1.5f);
            l.setSpeedAccuracyMetersPerSecond(0.5f);
            l.setBearingAccuracyDegrees(config.speedMps > 0 ? 5f : 180f);
        }
        return l;
    }

    private void shutDown() {
        handler.removeCallbacks(tick);
        for (String p : new ArrayList<>(activeProviders)) removeProvider(p);
        running = false;
        config = null;
        currentLat = currentLon = Double.NaN;
        jitterE = jitterN = 0;
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
        notifyListeners();
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacks(tick);
        if (running || !activeProviders.isEmpty()) {
            for (String p : new ArrayList<>(activeProviders)) removeProvider(p);
            running = false;
            config = null;
            currentLat = currentLon = Double.NaN;
            notifyListeners();
        }
        super.onDestroy();
    }

    // ---- Notification -------------------------------------------------------------------------

    private void goForeground() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);
        } else {
            startForeground(NOTIFICATION_ID, buildNotification());
        }
    }

    @SuppressWarnings("deprecation")
    private Notification buildNotification() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(new NotificationChannel(CHANNEL,
                    getString(R.string.spoof_channel), NotificationManager.IMPORTANCE_LOW));
        }
        Notification.Builder b = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL) : new Notification.Builder(this);
        PendingIntent open = PendingIntent.getActivity(this, 5, new Intent(this, MainActivity.class),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent stop = PendingIntent.getService(this, 6,
                new Intent(this, SpoofService.class).setAction(ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        String text = Double.isNaN(currentLat) ? getString(R.string.recorder_starting)
                : Format.coordinates(currentLat, currentLon)
                + (config != null && config.speedMps > 0 ? " · " + Format.speed(config.speedMps) : "");
        return b.setSmallIcon(R.drawable.ic_stat_record)
                .setContentTitle(getString(R.string.spoof_notification_title))
                .setContentText(text)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(open)
                .addAction(new Notification.Action.Builder(null, getString(R.string.spoof_stop), stop).build())
                .build();
    }

    private void updateNotification() {
        if (!running) return;
        getSystemService(NotificationManager.class).notify(NOTIFICATION_ID, buildNotification());
    }
}

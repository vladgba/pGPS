package x.vladgba.gps.gnss;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.GnssAutomaticGainControl;
import android.location.GnssMeasurement;
import android.location.GnssMeasurementsEvent;
import android.location.GnssStatus;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.location.OnNmeaMessageListener;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Single owner of the platform GNSS callbacks (location, satellite status, NMEA, raw
 * measurements). One instance per process ({@link #get}): the screen and the background
 * recorder share it, and it runs while at least one of them holds it ({@link #acquire}).
 * Notifies listeners on the main thread. Not thread-safe: use from the UI thread.
 */
public final class GnssMonitor {

    public interface Listener {
        /** Location, satellites, engine state changed, or the 1 s refresh tick fired. */
        void onGnssStateChanged(GnssMonitor monitor);

        /** A raw NMEA sentence arrived; it is already in {@link #getNmeaLog()}. */
        default void onNmeaMessage(GnssMonitor monitor, String sentence) {
        }

        /** A new GNSS fix (before the state-changed notification). */
        default void onLocation(GnssMonitor monitor, Location location) {
        }

        /** A new satellite status epoch (before the state-changed notification). */
        default void onSatelliteStatus(GnssMonitor monitor, List<SatelliteInfo> satellites) {
        }

        /** Raw measurements (pseudoranges, carrier phase), when the device provides them. */
        default void onMeasurements(GnssMonitor monitor, GnssMeasurementsEvent event) {
        }
    }

    private static GnssMonitor instance;

    /** Process-wide instance. */
    public static synchronized GnssMonitor get(Context context) {
        if (instance == null) instance = new GnssMonitor(context);
        return instance;
    }

    private final Set<Object> holders = new HashSet<>();
    private final QualityHistory history = new QualityHistory();
    private final PositionAverager averager = new PositionAverager();

    public static final int NMEA_LOG_CAPACITY = 500;

    /** A fix older than this is reported as lost. */
    private static final long FIX_STALE_MS = 3_000;
    private static final long TICK_MS = 1_000;
    private static final long MIN_UPDATE_INTERVAL_MS = 1_000;
    private static final long NETWORK_UPDATE_INTERVAL_MS = 30_000;

    private final Context appContext;
    private final LocationManager locationManager;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final List<Listener> listeners = new ArrayList<>();
    private final NmeaFix nmeaFix = new NmeaFix();
    private final ArrayDeque<String> nmeaLog = new ArrayDeque<>(NMEA_LOG_CAPACITY);
    private final IntegrityAnalyzer integrity = new IntegrityAnalyzer();
    /** Last GnssMeasurementsEvent.Callback status, -1 before the platform answered. */
    private int measurementsStatus = -1;

    private boolean running;
    private boolean engineOn;
    private long startedAtElapsedMs;
    private int ttffMs = -1;
    private Location location;
    private List<SatelliteInfo> satellites = Collections.emptyList();
    private List<ConstellationCount> counts = Collections.emptyList();
    private int satellitesInView;
    private int satellitesUsed;
    private long nmeaReceived;
    private long measurementEpochs;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            notifyStateChanged();
            if (running) handler.postDelayed(this, TICK_MS);
        }
    };

    // LocationListener methods are all implemented: before API 30 they are abstract.
    private final LocationListener locationListener = new LocationListener() {
        @Override
        public void onLocationChanged(Location loc) {
            location = loc;
            long fixElapsedMs = loc.getElapsedRealtimeNanos() / 1_000_000L;
            long ageMs = SystemClock.elapsedRealtime() - fixElapsedMs;
            long clockOffsetMs = loc.getTime() - (System.currentTimeMillis() - ageMs);
            integrity.onGnssFix(loc.getLatitude(), loc.getLongitude(), isMock(loc), fixElapsedMs,
                    loc.getTime(), clockOffsetMs, SystemClock.elapsedRealtime());
            averager.onFix(loc);
            for (Listener l : new ArrayList<>(listeners)) l.onLocation(GnssMonitor.this, loc);
            notifyStateChanged();
        }

        @Override
        public void onProviderEnabled(String provider) {
            notifyStateChanged();
        }

        @Override
        public void onProviderDisabled(String provider) {
            satellites = Collections.emptyList();
            counts = Collections.emptyList();
            satellitesInView = satellitesUsed = 0;
            notifyStateChanged();
        }

        @Override
        @SuppressWarnings("deprecation")
        public void onStatusChanged(String provider, int status, Bundle extras) {
        }
    };

    private final GnssStatus.Callback statusCallback = new GnssStatus.Callback() {
        @Override
        public void onStarted() {
            engineOn = true;
            notifyStateChanged();
        }

        @Override
        public void onStopped() {
            engineOn = false;
            notifyStateChanged();
        }

        @Override
        public void onFirstFix(int ttffMillis) {
            ttffMs = ttffMillis;
            notifyStateChanged();
        }

        @Override
        public void onSatelliteStatusChanged(GnssStatus status) {
            setSatellites(SatelliteInfo.fromStatus(status));
            history.onEpoch(SystemClock.elapsedRealtime(), GnssMonitor.this);
            for (Listener l : new ArrayList<>(listeners)) l.onSatelliteStatus(GnssMonitor.this, satellites);
            notifyStateChanged();
        }
    };

    private final OnNmeaMessageListener nmeaListener = (message, timestamp) -> onNmea(message);

    /** Wi-Fi/cell position, used only as an independent reference for spoofing checks. */
    private final LocationListener networkListener = new LocationListener() {
        @Override
        public void onLocationChanged(Location loc) {
            integrity.onNetworkFix(loc.getLatitude(), loc.getLongitude(),
                    loc.hasAccuracy() ? loc.getAccuracy() : 0f, loc.getElapsedRealtimeNanos() / 1_000_000L);
        }

        @Override
        public void onProviderEnabled(String provider) {
        }

        @Override
        public void onProviderDisabled(String provider) {
        }

        @Override
        @SuppressWarnings("deprecation")
        public void onStatusChanged(String provider, int status, Bundle extras) {
        }
    };

    /** Raw measurements: AGC for the jamming checks, everything for the RINEX recorder. */
    private final GnssMeasurementsEvent.Callback measurementsCallback = new GnssMeasurementsEvent.Callback() {
        @Override
        public void onGnssMeasurementsReceived(GnssMeasurementsEvent event) {
            measurementEpochs++;
            Map<String, Double> agc = agcByBand(event);
            if (!agc.isEmpty()) integrity.onAgc(agc, SystemClock.elapsedRealtime());
            for (Listener l : new ArrayList<>(listeners)) l.onMeasurements(GnssMonitor.this, event);
        }

        @Override
        public void onStatusChanged(int status) {
            measurementsStatus = status;
        }
    };

    private GnssMonitor(Context context) {
        appContext = context.getApplicationContext();
        locationManager = (LocationManager) appContext.getSystemService(Context.LOCATION_SERVICE);
    }

    /**
     * Registers a holder (the visible screen, the recorder service…) and starts GNSS if it is
     * not running yet. Calling it again with the same holder retries a start that failed, e.g.
     * right after the permission was granted.
     *
     * @return whether GNSS is running
     */
    public boolean acquire(Object holder) {
        holders.add(holder);
        return start();
    }

    /** Drops a holder; GNSS stops once nobody holds it. */
    public void release(Object holder) {
        holders.remove(holder);
        if (holders.isEmpty()) stop();
    }

    public void addListener(Listener listener) {
        if (!listeners.contains(listener)) listeners.add(listener);
    }

    public void removeListener(Listener listener) {
        listeners.remove(listener);
    }

    public boolean hasPermission() {
        return appContext.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
    }

    /**
     * Registers for GNSS updates. Safe to call repeatedly.
     *
     * @return false when the permission is missing or the platform refused the registration
     */
    @SuppressLint("MissingPermission") // checked by hasPermission()
    private boolean start() {
        if (running) return true;
        if (locationManager == null || !hasPermission()) return false;
        try {
            locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER,
                    MIN_UPDATE_INTERVAL_MS, 0f, locationListener, Looper.getMainLooper());
            locationManager.registerGnssStatusCallback(statusCallback, handler);
            locationManager.addNmeaListener(nmeaListener, handler);
        } catch (SecurityException | IllegalArgumentException e) {
            unregisterAll();
            return false;
        }
        registerIntegritySources();
        running = true;
        startedAtElapsedMs = SystemClock.elapsedRealtime();
        ttffMs = -1;
        nmeaFix.reset();
        handler.postDelayed(tick, TICK_MS);
        notifyStateChanged();
        return true;
    }

    private void stop() {
        if (!running) return;
        unregisterAll();
        running = false;
        engineOn = false;
        handler.removeCallbacks(tick);
        notifyStateChanged();
    }

    /** Optional inputs: a failure here only leaves the matching integrity checks "unknown". */
    @SuppressLint("MissingPermission") // only called from start(), after the permission check
    private void registerIntegritySources() {
        measurementsStatus = -1;
        try {
            locationManager.registerGnssMeasurementsCallback(measurementsCallback, handler);
        } catch (SecurityException | IllegalArgumentException e) {
            measurementsStatus = GnssMeasurementsEvent.Callback.STATUS_NOT_SUPPORTED;
        }
        try {
            if (locationManager.getAllProviders().contains(LocationManager.NETWORK_PROVIDER)) {
                Location last = locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
                if (last != null) networkListener.onLocationChanged(last);
                locationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER,
                        NETWORK_UPDATE_INTERVAL_MS, 0f, networkListener, Looper.getMainLooper());
            }
        } catch (SecurityException | IllegalArgumentException e) {
            // No network location: the distance check reports "unknown".
        }
    }

    private void unregisterAll() {
        if (locationManager == null) return;
        locationManager.removeUpdates(locationListener);
        locationManager.removeUpdates(networkListener);
        locationManager.unregisterGnssStatusCallback(statusCallback);
        locationManager.unregisterGnssMeasurementsCallback(measurementsCallback);
        locationManager.removeNmeaListener(nmeaListener);
    }

    /**
     * AGC per band. API 33+ reports it once per band in the event; older versions repeat it on
     * every measurement of that band (deprecated there since 33).
     */
    @SuppressWarnings("deprecation")
    private static Map<String, Double> agcByBand(GnssMeasurementsEvent event) {
        List<String> keys = new ArrayList<>();
        List<Double> levels = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            for (GnssAutomaticGainControl agc : event.getGnssAutomaticGainControls()) {
                keys.add(bandKey(agc.getConstellationType(), agc.getCarrierFrequencyHz()));
                levels.add(agc.getLevelDb());
            }
        }
        if (keys.isEmpty() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            for (GnssMeasurement m : event.getMeasurements()) {
                if (!m.hasAutomaticGainControlLevelDb()) continue;
                float carrier = m.hasCarrierFrequencyHz() ? m.getCarrierFrequencyHz() : Float.NaN;
                keys.add(bandKey(m.getConstellationType(), carrier));
                levels.add(m.getAutomaticGainControlLevelDb());
            }
        }
        return IntegrityAnalyzer.averageByBand(keys, levels);
    }

    private static String bandKey(int constellation, double carrierHz) {
        String band = Constellations.band(constellation, (float) carrierHz);
        return Constellations.name(constellation) + (band != null ? " " + band : "");
    }

    @SuppressWarnings("deprecation")
    private static boolean isMock(Location loc) {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ? loc.isMock() : loc.isFromMockProvider();
    }

    /**
     * Deletes cached almanac/ephemeris/time/position so the next fix is a cold start, then
     * restarts the session so TTFF is measured again.
     *
     * @return whether the platform accepted the command
     */
    public boolean clearAidingData() {
        if (locationManager == null) return false;
        boolean wasRunning = running;
        stop();
        boolean accepted;
        try {
            accepted = locationManager.sendExtraCommand(LocationManager.GPS_PROVIDER, "delete_aiding_data", null);
        } catch (SecurityException e) {
            accepted = false;
        }
        location = null;
        setSatellites(Collections.emptyList());
        integrity.reset();
        if (wasRunning) start();
        return accepted;
    }

    public void clearNmeaLog() {
        nmeaLog.clear();
        notifyStateChanged();
    }

    private void onNmea(String message) {
        if (message == null) return;
        String line = message.trim();
        if (line.isEmpty()) return;
        nmeaReceived++;
        if (nmeaLog.size() >= NMEA_LOG_CAPACITY) nmeaLog.removeFirst();
        nmeaLog.addLast(line);
        nmeaFix.parse(line, SystemClock.elapsedRealtime());
        for (Listener l : new ArrayList<>(listeners)) l.onNmeaMessage(this, line);
    }

    private void setSatellites(List<SatelliteInfo> list) {
        satellites = list;
        if (!list.isEmpty() || running) integrity.onSatellites(list, SystemClock.elapsedRealtime());
        counts = ConstellationCount.of(list);
        Set<Long> inView = new HashSet<>();
        Set<Long> used = new HashSet<>();
        for (SatelliteInfo s : list) {
            inView.add(s.key());
            if (s.usedInFix) used.add(s.key());
        }
        satellitesInView = inView.size();
        satellitesUsed = used.size();
    }

    private void notifyStateChanged() {
        for (Listener l : new ArrayList<>(listeners)) l.onGnssStateChanged(this);
    }

    // ---- State accessors -------------------------------------------------------------------

    public boolean isRunning() {
        return running;
    }

    /** Whether the GNSS chipset reported it is powered (GnssStatus.Callback#onStarted). */
    public boolean isEngineOn() {
        return engineOn;
    }

    public boolean isProviderEnabled() {
        return locationManager != null && locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER);
    }

    /** Last location received in this process, possibly stale; see {@link #hasFreshFix()}. */
    public Location getLocation() {
        return location;
    }

    /** Milliseconds since the last fix was computed, or -1 without a fix. */
    public long getFixAgeMs() {
        if (location == null) return -1;
        long ageNanos = SystemClock.elapsedRealtimeNanos() - location.getElapsedRealtimeNanos();
        return Math.max(0, ageNanos / 1_000_000L);
    }

    public boolean hasFreshFix() {
        long age = getFixAgeMs();
        return running && age >= 0 && age <= FIX_STALE_MS;
    }

    /** Time to first fix of the current session in ms, or -1 if not reached yet. */
    public int getTtffMs() {
        return ttffMs;
    }

    public long getSessionDurationMs() {
        return running ? SystemClock.elapsedRealtime() - startedAtElapsedMs : 0;
    }

    /** One entry per tracked signal, sorted by constellation then SVID. */
    public List<SatelliteInfo> getSatellites() {
        return satellites;
    }

    public List<ConstellationCount> getConstellationCounts() {
        return counts;
    }

    /** Distinct satellites (not signals) in view. */
    public int getSatellitesInView() {
        return satellitesInView;
    }

    /** Distinct satellites (not signals) used in the fix. */
    public int getSatellitesUsed() {
        return satellitesUsed;
    }

    /** NMEA-derived values; check {@link #isNmeaFresh()} before trusting them. */
    public NmeaFix getNmeaFix() {
        return nmeaFix;
    }

    public boolean isNmeaFresh() {
        return running && nmeaFix.isFresh(SystemClock.elapsedRealtime());
    }

    /** Oldest first, at most {@link #NMEA_LOG_CAPACITY} sentences. */
    public ArrayDeque<String> getNmeaLog() {
        return nmeaLog;
    }

    public long getNmeaReceived() {
        return nmeaReceived;
    }

    /** Fresh jamming/spoofing evaluation. */
    public IntegrityReport getIntegrityReport() {
        return integrity.evaluate(SystemClock.elapsedRealtime());
    }

    /** History series for the chart, and {@link IntegrityAnalyzer#reset()}. */
    public IntegrityAnalyzer getIntegrityAnalyzer() {
        return integrity;
    }

    /** GnssMeasurementsEvent.Callback status, or -1 if not known yet. */
    public int getMeasurementsStatus() {
        return measurementsStatus;
    }

    /** Raw measurement events received since the process started (0 = none / unsupported). */
    public long getMeasurementEpochs() {
        return measurementEpochs;
    }

    public QualityHistory getHistory() {
        return history;
    }

    public PositionAverager getAverager() {
        return averager;
    }

    public Context getContext() {
        return appContext;
    }
}

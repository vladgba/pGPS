package x.vladgba.gps.nav;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.location.Location;
import android.os.Build;
import android.os.IBinder;

import x.vladgba.gps.MainActivity;
import x.vladgba.gps.R;
import x.vladgba.gps.gnss.GnssMonitor;
import x.vladgba.gps.ui.Format;

import java.util.ArrayList;
import java.util.List;

/**
 * Foreground "location" service for backtracking: records the breadcrumb trail on the way out,
 * then guides back along it, both with the screen off. Alerts when the start is reached and when
 * the user strays from the trail.
 */
public final class BacktrackService extends Service implements GnssMonitor.Listener {

    public enum Mode { OFF, RECORDING, RETURNING }

    public interface Listener {
        void onBacktrackChanged();
    }

    private static final String ACTION_RECORD = "x.vladgba.gps.backtrack.RECORD";
    private static final String ACTION_RETURN = "x.vladgba.gps.backtrack.RETURN";
    private static final String ACTION_STOP = "x.vladgba.gps.backtrack.STOP";
    private static final String CHANNEL_ONGOING = "backtrack";
    private static final String CHANNEL_ALERTS = "backtrack_alerts";
    private static final int NOTIFICATION_ID = 2;
    private static final int ALERT_ID = 3;
    /** Off-trail alert threshold, with hysteresis so it does not repeat on noise. */
    private static final double OFF_TRAIL_ALERT_M = 30;
    private static final double OFF_TRAIL_CLEAR_M = 15;

    private static Mode mode = Mode.OFF;
    private static BacktrackNavigator navigator;
    private static BacktrackNavigator.Guidance guidance;
    private static final List<Listener> listeners = new ArrayList<>();

    private boolean offTrailAlerted;
    private boolean arrivedAlerted;

    // ---- Static API for the UI ----------------------------------------------------------------

    public static void startRecording(Context context) {
        start(context, ACTION_RECORD);
    }

    public static void startReturn(Context context) {
        start(context, ACTION_RETURN);
    }

    public static void stop(Context context) {
        context.startService(new Intent(context, BacktrackService.class).setAction(ACTION_STOP));
    }

    private static void start(Context context, String action) {
        Intent i = new Intent(context, BacktrackService.class).setAction(action);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(i);
        else context.startService(i);
    }

    public static Mode getMode() {
        return mode;
    }

    /** Latest guidance while returning, or null. */
    public static BacktrackNavigator.Guidance getGuidance() {
        return guidance;
    }

    public static void addListener(Listener l) {
        if (!listeners.contains(l)) listeners.add(l);
    }

    public static void removeListener(Listener l) {
        listeners.remove(l);
    }

    private static void notifyListeners() {
        for (Listener l : new ArrayList<>(listeners)) l.onBacktrackChanged();
    }

    // ---- Service ------------------------------------------------------------------------------

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : null;
        if (ACTION_RECORD.equals(action) || ACTION_RETURN.equals(action)) {
            goForeground();
            GnssMonitor monitor = GnssMonitor.get(this);
            if (!monitor.hasPermission()) {
                shutDown();
                return START_NOT_STICKY;
            }
            if (ACTION_RECORD.equals(action)) {
                mode = Mode.RECORDING;
                guidance = null;
                navigator = null;
            } else {
                mode = Mode.RETURNING;
                navigator = BacktrackNavigator.of(Trail.get(this).getPoints());
                guidance = null;
                offTrailAlerted = false;
                arrivedAlerted = false;
                Location last = monitor.getLocation();
                if (last != null) onLocation(monitor, last);
            }
            monitor.addListener(this);
            monitor.acquire(this);
            updateNotification();
            notifyListeners();
        } else {
            shutDown();
        }
        return START_NOT_STICKY;
    }

    private void shutDown() {
        GnssMonitor monitor = GnssMonitor.get(this);
        monitor.removeListener(this);
        monitor.release(this);
        mode = Mode.OFF;
        navigator = null;
        guidance = null;
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
        notifyListeners();
    }

    @Override
    public void onDestroy() {
        if (mode != Mode.OFF) {
            GnssMonitor monitor = GnssMonitor.get(this);
            monitor.removeListener(this);
            monitor.release(this);
            mode = Mode.OFF;
            notifyListeners();
        }
        super.onDestroy();
    }

    @Override
    public void onGnssStateChanged(GnssMonitor monitor) {
    }

    @Override
    public void onLocation(GnssMonitor monitor, Location loc) {
        if (mode == Mode.RECORDING) {
            if (Trail.get(this).offer(loc)) updateNotification();
        } else if (mode == Mode.RETURNING && navigator != null) {
            guidance = navigator.update(loc.getLatitude(), loc.getLongitude());
            if (guidance != null) checkAlerts();
            updateNotification();
            notifyListeners();
        }
    }

    private void checkAlerts() {
        if (guidance.arrived && !arrivedAlerted) {
            arrivedAlerted = true;
            alert(getString(R.string.bt_alert_arrived));
        }
        if (!offTrailAlerted && guidance.offTrail > OFF_TRAIL_ALERT_M) {
            offTrailAlerted = true;
            alert(getString(R.string.bt_alert_off_trail, Format.distance(guidance.offTrail)));
        } else if (offTrailAlerted && guidance.offTrail < OFF_TRAIL_CLEAR_M) {
            offTrailAlerted = false;
        }
    }

    // ---- Notifications ------------------------------------------------------------------------

    private void goForeground() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);
        } else {
            startForeground(NOTIFICATION_ID, buildNotification());
        }
    }

    private void ensureChannels(NotificationManager nm) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        if (nm.getNotificationChannel(CHANNEL_ONGOING) == null) {
            nm.createNotificationChannel(new NotificationChannel(CHANNEL_ONGOING,
                    getString(R.string.bt_channel), NotificationManager.IMPORTANCE_LOW));
        }
        if (nm.getNotificationChannel(CHANNEL_ALERTS) == null) {
            NotificationChannel alerts = new NotificationChannel(CHANNEL_ALERTS,
                    getString(R.string.bt_channel_alerts), NotificationManager.IMPORTANCE_HIGH);
            alerts.enableVibration(true);
            nm.createNotificationChannel(alerts);
        }
    }

    @SuppressWarnings("deprecation")
    private Notification.Builder builder(String channel) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        ensureChannels(nm);
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, channel) : new Notification.Builder(this);
    }

    private Notification buildNotification() {
        PendingIntent open = PendingIntent.getActivity(this, 2, new Intent(this, MainActivity.class),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent stop = PendingIntent.getService(this, 3,
                new Intent(this, BacktrackService.class).setAction(ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        String title;
        String text;
        Trail trail = Trail.get(this);
        if (mode == Mode.RETURNING) {
            title = getString(R.string.bt_notification_returning);
            text = guidance == null ? getString(R.string.recorder_starting)
                    : guidance.arrived ? getString(R.string.bt_arrived)
                    : getString(R.string.bt_notification_remaining, Format.distance(guidance.remaining),
                    Format.bearing(guidance.bearingToTarget));
        } else {
            title = getString(R.string.bt_notification_recording);
            text = getString(R.string.bt_notification_trail, trail.getPoints().size(), Format.distance(trail.getLengthM()));
        }
        return builder(CHANNEL_ONGOING)
                .setSmallIcon(R.drawable.ic_stat_record)
                .setContentTitle(title)
                .setContentText(text)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(open)
                .addAction(new Notification.Action.Builder(null, getString(R.string.recorder_stop), stop).build())
                .build();
    }

    private void updateNotification() {
        getSystemService(NotificationManager.class).notify(NOTIFICATION_ID, buildNotification());
    }

    private void alert(String text) {
        PendingIntent open = PendingIntent.getActivity(this, 4, new Intent(this, MainActivity.class),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification n = builder(CHANNEL_ALERTS)
                .setSmallIcon(R.drawable.ic_stat_record)
                .setContentTitle(getString(R.string.tool_backtrack))
                .setContentText(text)
                .setAutoCancel(true)
                .setContentIntent(open)
                .build();
        getSystemService(NotificationManager.class).notify(ALERT_ID, n);
    }
}

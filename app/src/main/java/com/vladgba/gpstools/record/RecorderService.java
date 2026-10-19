package com.vladgba.gpstools.record;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;

import com.vladgba.gpstools.MainActivity;
import com.vladgba.gpstools.R;
import com.vladgba.gpstools.gnss.GnssMonitor;
import com.vladgba.gpstools.ui.Format;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Foreground "location" service that keeps GNSS running and writes a session while the screen
 * is off. The UI reads progress through the static accessors and {@link Listener}.
 */
public final class RecorderService extends Service {

    public interface Listener {
        void onRecorderChanged();
    }

    private static final String ACTION_START = "com.vladgba.gpstools.record.START";
    private static final String ACTION_STOP = "com.vladgba.gpstools.record.STOP";
    private static final String CHANNEL_ID = "recording";
    private static final int NOTIFICATION_ID = 1;

    private static SessionWriter writer;
    private static long startedAtElapsedMs;
    private static String lastError;
    private static final List<Listener> listeners = new ArrayList<>();

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (writer == null) return;
            updateNotification();
            notifyListeners();
            handler.postDelayed(this, 1000);
        }
    };

    // ---- Static API for the UI ----------------------------------------------------------------

    public static void start(Context context) {
        Intent i = new Intent(context, RecorderService.class).setAction(ACTION_START);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(i);
        else context.startService(i);
    }

    public static void stop(Context context) {
        context.startService(new Intent(context, RecorderService.class).setAction(ACTION_STOP));
    }

    public static boolean isRecording() {
        return writer != null;
    }

    public static long getElapsedMs() {
        return writer != null ? SystemClock.elapsedRealtime() - startedAtElapsedMs : 0;
    }

    public static long getFixCount() {
        return writer != null ? writer.getFixCount() : 0;
    }

    public static long getEpochCount() {
        return writer != null ? writer.getEpochCount() : 0;
    }

    public static long getNmeaCount() {
        return writer != null ? writer.getNmeaCount() : 0;
    }

    public static int getRinexEpochs() {
        return writer != null ? writer.getRinexEpochs() : 0;
    }

    public static boolean isRecordingRinex() {
        return writer != null && writer.isRecordingRinex();
    }

    public static File getSessionDir() {
        return writer != null ? writer.getDir() : null;
    }

    /** Last write error of the current or previous session, or null. */
    public static String getError() {
        return writer != null && writer.getError() != null ? writer.getError() : lastError;
    }

    public static void addListener(Listener l) {
        if (!listeners.contains(l)) listeners.add(l);
    }

    public static void removeListener(Listener l) {
        listeners.remove(l);
    }

    private static void notifyListeners() {
        for (Listener l : new ArrayList<>(listeners)) l.onRecorderChanged();
    }

    // ---- Service ------------------------------------------------------------------------------

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : null;
        if (ACTION_START.equals(action)) {
            startRecording();
        } else {
            stopRecording();
        }
        return START_NOT_STICKY;
    }

    private void startRecording() {
        // startForeground must happen promptly after startForegroundService, even on failure.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);
        } else {
            startForeground(NOTIFICATION_ID, buildNotification());
        }
        if (writer != null) return;
        GnssMonitor monitor = GnssMonitor.get(this);
        if (!monitor.hasPermission()) {
            lastError = getString(R.string.toast_need_permission);
            stopRecording();
            return;
        }
        long now = System.currentTimeMillis();
        String name = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.ROOT).format(new Date(now));
        File dir = new File(SessionStore.sessionsRoot(this), name);
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        Set<RecordingFormat> formats = SessionStore.formats(this);
        writer = new SessionWriter(dir, formats, now);
        lastError = null;
        startedAtElapsedMs = SystemClock.elapsedRealtime();
        monitor.addListener(writer);
        monitor.acquire(this);
        handler.post(tick);
        notifyListeners();
    }

    private void stopRecording() {
        handler.removeCallbacks(tick);
        if (writer != null) {
            GnssMonitor monitor = GnssMonitor.get(this);
            monitor.removeListener(writer);
            writer.close();
            lastError = writer.getError();
            writer = null;
            monitor.release(this);
        }
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
        notifyListeners();
    }

    @Override
    public void onDestroy() {
        // Killed by the system while recording: still finish the files.
        if (writer != null) stopRecording();
        super.onDestroy();
    }

    private Notification buildNotification() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                        getString(R.string.recorder_channel), NotificationManager.IMPORTANCE_LOW);
                nm.createNotificationChannel(channel);
            }
            b = new Notification.Builder(this, CHANNEL_ID);
        } else {
            b = new Notification.Builder(this);
        }
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent stop = PendingIntent.getService(this, 1,
                new Intent(this, RecorderService.class).setAction(ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        String text = writer != null
                ? getString(R.string.recorder_notification_text, Format.duration(getElapsedMs()), getFixCount())
                : getString(R.string.recorder_starting);
        return b.setSmallIcon(R.drawable.ic_stat_record)
                .setContentTitle(getString(R.string.recorder_notification_title))
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
}

package com.vladgba.gpstools.ui;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.location.GnssMeasurementsEvent;
import android.net.Uri;
import android.os.Build;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.vladgba.gpstools.R;
import com.vladgba.gpstools.gnss.GnssMonitor;
import com.vladgba.gpstools.record.RecorderService;
import com.vladgba.gpstools.record.RecordingFormat;
import com.vladgba.gpstools.record.SessionFileProvider;
import com.vladgba.gpstools.record.SessionStore;

import java.io.File;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Start/stop recording, choose formats, share or delete recorded sessions. */
final class RecorderPage implements Page, RecorderService.Listener {

    private static final int REQUEST_NOTIFICATIONS = 2;

    private final Activity activity;
    private final ScrollView root;
    private final TextView state;
    private final TextView details;
    private final TextView error;
    private final TextView rawNote;
    private final Button startStop;
    private final Map<RecordingFormat, CheckBox> formatBoxes = new EnumMap<>(RecordingFormat.class);
    private final LinearLayout sessions;
    private boolean wasRecording;
    private GnssMonitor monitor;

    RecorderPage(Activity activity) {
        this.activity = activity;
        root = new ScrollView(activity);
        LinearLayout list = new LinearLayout(activity);
        list.setOrientation(LinearLayout.VERTICAL);
        int pad = Ui.dp(activity, 16);
        list.setPadding(pad, pad, pad, pad);
        root.addView(list);

        state = text(20, R.color.text_primary);
        state.setTypeface(Typeface.DEFAULT_BOLD);
        list.addView(state);
        details = text(13, R.color.text_secondary);
        list.addView(details);
        error = text(13, R.color.state_bad);
        error.setVisibility(View.GONE);
        list.addView(error);

        startStop = new Button(activity);
        startStop.setOnClickListener(v -> toggle());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Ui.dp(activity, 12);
        list.addView(startStop, lp);

        rawNote = text(13, R.color.state_warn);
        rawNote.setText(R.string.recorder_rinex_unavailable);
        rawNote.setPadding(0, Ui.dp(activity, 8), 0, 0);
        rawNote.setVisibility(View.GONE);
        list.addView(rawNote);

        list.addView(section(R.string.recorder_formats));
        Set<RecordingFormat> enabled = SessionStore.formats(activity);
        int[] labels = {R.string.format_nmea, R.string.format_satellites, R.string.format_fixes,
                R.string.format_gpx, R.string.format_kml, R.string.format_rinex};
        RecordingFormat[] formats = RecordingFormat.values();
        for (int i = 0; i < formats.length; i++) {
            CheckBox box = new CheckBox(activity);
            box.setText(labels[i]);
            box.setTextColor(activity.getColor(R.color.text_primary));
            box.setChecked(enabled.contains(formats[i]));
            box.setOnCheckedChangeListener((b, checked) -> saveFormats());
            formatBoxes.put(formats[i], box);
            list.addView(box);
        }

        list.addView(section(R.string.recorder_sessions));
        sessions = new LinearLayout(activity);
        sessions.setOrientation(LinearLayout.VERTICAL);
        list.addView(sessions);

        TextView hint = text(12, R.color.text_secondary);
        hint.setText(activity.getString(R.string.recorder_location_hint, activity.getPackageName()));
        hint.setPadding(0, Ui.dp(activity, 12), 0, 0);
        list.addView(hint);

        RecorderService.addListener(this);
        refreshSessions();
    }

    private void saveFormats() {
        Set<RecordingFormat> set = java.util.EnumSet.noneOf(RecordingFormat.class);
        for (Map.Entry<RecordingFormat, CheckBox> e : formatBoxes.entrySet()) {
            if (e.getValue().isChecked()) set.add(e.getKey());
        }
        SessionStore.setFormats(activity, set);
    }

    private void toggle() {
        if (RecorderService.isRecording()) {
            RecorderService.stop(activity);
            return;
        }
        if (monitor == null || !monitor.hasPermission()) {
            Toast.makeText(activity, R.string.toast_need_permission, Toast.LENGTH_SHORT).show();
            return;
        }
        // Without this permission the ongoing notification is hidden; recording still works.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && activity.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            activity.requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQUEST_NOTIFICATIONS);
        }
        RecorderService.start(activity);
    }

    @Override
    public View getView() {
        return root;
    }

    @Override
    public void update(GnssMonitor m) {
        monitor = m;
        render();
    }

    @Override
    public void onRecorderChanged() {
        render();
    }

    private void render() {
        boolean recording = RecorderService.isRecording();
        if (recording) {
            state.setText(Format.duration(RecorderService.getElapsedMs()));
            state.setTextColor(activity.getColor(R.color.state_bad));
            String text = activity.getString(R.string.recorder_status, Format.duration(RecorderService.getElapsedMs()),
                    RecorderService.getFixCount(), RecorderService.getEpochCount(), RecorderService.getNmeaCount());
            if (RecorderService.isRecordingRinex()) {
                text += "\n" + activity.getString(R.string.recorder_rinex_status, RecorderService.getRinexEpochs());
            }
            File dir = RecorderService.getSessionDir();
            if (dir != null) text += "\n" + dir.getName() + " · " + Format.fileSize(SessionStore.size(dir));
            details.setText(text);
            startStop.setText(R.string.recorder_stop);
        } else {
            state.setText(R.string.recorder_idle);
            state.setTextColor(activity.getColor(R.color.text_primary));
            details.setText("");
            startStop.setText(R.string.recorder_start);
        }
        for (CheckBox box : formatBoxes.values()) box.setEnabled(!recording);

        String err = RecorderService.getError();
        error.setVisibility(err != null ? View.VISIBLE : View.GONE);
        if (err != null) error.setText(activity.getString(R.string.recorder_error, err));

        boolean noRaw = monitor != null && (monitor.getMeasurementsStatus() == GnssMeasurementsEvent.Callback.STATUS_NOT_SUPPORTED
                || (monitor.isRunning() && monitor.getSessionDurationMs() > 10_000 && monitor.getMeasurementEpochs() == 0));
        rawNote.setVisibility(noRaw && formatBoxes.get(RecordingFormat.RINEX).isChecked() ? View.VISIBLE : View.GONE);

        if (wasRecording != recording) refreshSessions();
        wasRecording = recording;
    }

    private void refreshSessions() {
        sessions.removeAllViews();
        List<File> list = SessionStore.list(activity);
        if (list.isEmpty()) {
            TextView none = text(14, R.color.text_secondary);
            none.setText(R.string.recorder_no_sessions);
            sessions.addView(none);
            return;
        }
        File active = RecorderService.getSessionDir();
        for (File session : list) {
            LinearLayout row = new LinearLayout(activity);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, Ui.dp(activity, 4), 0, Ui.dp(activity, 4));
            LinearLayout texts = new LinearLayout(activity);
            texts.setOrientation(LinearLayout.VERTICAL);
            TextView name = text(15, R.color.text_primary);
            name.setText(session.getName());
            texts.addView(name);
            TextView info = text(12, R.color.text_secondary);
            List<File> files = SessionStore.files(session);
            info.setText(activity.getString(R.string.recorder_session_info, files.size(), Format.fileSize(SessionStore.size(session))));
            texts.addView(info);
            row.addView(texts, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            boolean isActive = session.equals(active);
            if (!isActive) {
                row.addView(smallButton(R.string.recorder_share, v -> share(session)));
                row.addView(smallButton(R.string.recorder_delete, v -> confirmDelete(session)));
            }
            sessions.addView(row);
        }
    }

    private void share(File session) {
        ArrayList<Uri> uris = new ArrayList<>();
        for (File f : SessionStore.files(session)) uris.add(SessionFileProvider.uriFor(activity, f));
        if (uris.isEmpty()) return;
        Intent send = new Intent(Intent.ACTION_SEND_MULTIPLE)
                .setType("*/*")
                .putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
                .putExtra(Intent.EXTRA_SUBJECT, "GNSS session " + session.getName())
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        ClipData clip = ClipData.newRawUri(session.getName(), uris.get(0));
        for (int i = 1; i < uris.size(); i++) clip.addItem(new ClipData.Item(uris.get(i)));
        send.setClipData(clip);
        try {
            activity.startActivity(Intent.createChooser(send, activity.getString(R.string.recorder_share)));
        } catch (ActivityNotFoundException e) {
            Toast.makeText(activity, R.string.toast_no_app, Toast.LENGTH_SHORT).show();
        }
    }

    private void confirmDelete(File session) {
        new AlertDialog.Builder(activity, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle(R.string.recorder_delete_title)
                .setMessage(session.getName())
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.recorder_delete, (d, w) -> {
                    SessionStore.delete(session);
                    refreshSessions();
                })
                .show();
    }

    private Button smallButton(int text, View.OnClickListener l) {
        Button b = new Button(activity, null, android.R.attr.borderlessButtonStyle);
        b.setText(text);
        b.setTextColor(activity.getColor(R.color.accent));
        b.setOnClickListener(l);
        return b;
    }

    private TextView section(int res) {
        TextView t = text(12, R.color.accent);
        t.setText(res);
        t.setAllCaps(true);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setLetterSpacing(0.08f);
        t.setPadding(0, Ui.dp(activity, 20), 0, Ui.dp(activity, 6));
        return t;
    }

    private TextView text(float sp, int color) {
        TextView t = new TextView(activity);
        t.setTextSize(sp);
        t.setTextColor(activity.getColor(color));
        return t;
    }
}

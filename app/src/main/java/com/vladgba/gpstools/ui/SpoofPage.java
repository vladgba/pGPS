package com.vladgba.gpstools.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.location.Location;
import android.os.Build;
import android.provider.Settings;
import android.text.InputType;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.vladgba.gpstools.R;
import com.vladgba.gpstools.coords.CoordinateParser;
import com.vladgba.gpstools.gnss.GnssMonitor;
import com.vladgba.gpstools.nav.Waypoint;
import com.vladgba.gpstools.nav.WaypointStore;
import com.vladgba.gpstools.spoof.SpoofService;

import java.util.List;
import java.util.Locale;

/** Fake the device position through Developer options → "Select mock location app". */
final class SpoofPage implements Page, SpoofService.Listener {

    private static final String PREFS = "spoof";

    private final Activity activity;
    private final ScrollView root;
    private final TextView state;
    private final TextView setupStatus;
    private final Button setupButton;
    private final EditText coords;
    private final EditText altitude;
    private final EditText accuracy;
    private final EditText speed;
    private final EditText bearing;
    private final CheckBox jitter;
    private final CheckBox gps;
    private final CheckBox network;
    private final CheckBox fused;
    private final Button startButton;
    private final Button stopButton;
    private final TextView error;
    private GnssMonitor monitor;
    private boolean listening;

    SpoofPage(Activity activity) {
        this.activity = activity;
        root = new ScrollView(activity);
        LinearLayout list = new LinearLayout(activity);
        list.setOrientation(LinearLayout.VERTICAL);
        int pad = Ui.dp(activity, 16);
        list.setPadding(pad, pad, pad, pad);
        root.addView(list);

        state = new TextView(activity);
        state.setTextSize(20);
        state.setTypeface(Typeface.DEFAULT_BOLD);
        list.addView(state);

        setupStatus = text(14, R.color.text_primary);
        setupStatus.setPadding(0, Ui.dp(activity, 8), 0, 0);
        list.addView(setupStatus);
        setupButton = new Button(activity);
        setupButton.setText(R.string.spoof_open_dev);
        setupButton.setOnClickListener(v -> openDeveloperOptions());
        list.addView(setupButton);

        list.addView(label(R.string.spoof_coords));
        coords = edit(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        coords.setHint(R.string.spoof_coords_hint);
        list.addView(coords);
        LinearLayout pick = new LinearLayout(activity);
        pick.setOrientation(LinearLayout.HORIZONTAL);
        pick.addView(smallButton(R.string.spoof_use_current, v -> useCurrent()),
                new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        pick.addView(smallButton(R.string.spoof_use_waypoint, v -> pickWaypoint()),
                new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        list.addView(pick);

        int number = InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL;
        altitude = edit(number | InputType.TYPE_NUMBER_FLAG_SIGNED);
        accuracy = edit(number);
        speed = edit(number);
        bearing = edit(number);
        list.addView(row(R.string.spoof_altitude, altitude, R.string.spoof_accuracy, accuracy));
        list.addView(row(R.string.spoof_speed, speed, R.string.spoof_bearing, bearing));

        jitter = check(R.string.spoof_jitter);
        list.addView(jitter);
        list.addView(label(R.string.spoof_providers));
        gps = check(R.string.spoof_provider_gps);
        network = check(R.string.spoof_provider_network);
        fused = check(R.string.spoof_provider_fused);
        list.addView(gps);
        list.addView(network);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) list.addView(fused);

        LinearLayout buttons = new LinearLayout(activity);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        startButton = new Button(activity);
        startButton.setOnClickListener(v -> start());
        stopButton = new Button(activity);
        stopButton.setText(R.string.spoof_stop);
        stopButton.setOnClickListener(v -> SpoofService.stop(activity));
        buttons.addView(startButton, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        buttons.addView(stopButton, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        list.addView(buttons);

        error = text(13, R.color.state_bad);
        list.addView(error);

        TextView hint = text(12, R.color.text_secondary);
        hint.setText(R.string.spoof_hint);
        hint.setPadding(0, Ui.dp(activity, 12), 0, 0);
        list.addView(hint);

        loadPrefs();
    }

    // ---- View helpers -------------------------------------------------------------------------

    private TextView text(int sizeSp, int color) {
        TextView t = new TextView(activity);
        t.setTextSize(sizeSp);
        t.setTextColor(activity.getColor(color));
        return t;
    }

    private TextView label(int res) {
        TextView t = text(13, R.color.text_secondary);
        t.setText(res);
        t.setPadding(0, Ui.dp(activity, 12), 0, 0);
        return t;
    }

    private EditText edit(int inputType) {
        EditText e = new EditText(activity);
        e.setInputType(inputType);
        e.setSingleLine(true);
        e.setTextColor(activity.getColor(R.color.text_primary));
        e.setHintTextColor(activity.getColor(R.color.text_secondary));
        return e;
    }

    private CheckBox check(int res) {
        CheckBox c = new CheckBox(activity);
        c.setText(res);
        c.setTextColor(activity.getColor(R.color.text_primary));
        return c;
    }

    private Button smallButton(int res, View.OnClickListener l) {
        Button b = new Button(activity, null, android.R.attr.borderlessButtonStyle);
        b.setText(res);
        b.setTextColor(activity.getColor(R.color.accent));
        b.setOnClickListener(l);
        return b;
    }

    /** Two labelled fields side by side. */
    private LinearLayout row(int label1, EditText field1, int label2, EditText field2) {
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.addView(column(label1, field1), new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        View gap = new View(activity);
        row.addView(gap, new LinearLayout.LayoutParams(Ui.dp(activity, 12), 1));
        row.addView(column(label2, field2), new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        return row;
    }

    private LinearLayout column(int label, EditText field) {
        LinearLayout c = new LinearLayout(activity);
        c.setOrientation(LinearLayout.VERTICAL);
        c.addView(label(label));
        c.addView(field);
        return c;
    }

    // ---- Page ---------------------------------------------------------------------------------

    @Override
    public View getView() {
        return root;
    }

    @Override
    public void update(GnssMonitor m) {
        monitor = m;
        if (!listening) {
            SpoofService.addListener(this);
            listening = true;
        }
        render();
    }

    @Override
    public void onHidden() {
        SpoofService.removeListener(this);
        listening = false;
    }

    @Override
    public void onSpoofChanged() {
        // The listener list is static: drop a page whose activity is gone instead of leaking it.
        if (activity.isDestroyed()) {
            onHidden();
            return;
        }
        render();
    }

    private void render() {
        boolean selected = SpoofService.isMockAppSelected(activity);
        boolean running = SpoofService.isRunning();
        if (running) {
            state.setText(R.string.spoof_state_on);
            state.setTextColor(activity.getColor(R.color.state_bad));
        } else {
            state.setText(R.string.spoof_state_off);
            state.setTextColor(activity.getColor(R.color.text_primary));
        }
        StringBuilder status = new StringBuilder();
        if (selected) {
            status.append(activity.getString(R.string.spoof_setup_ok));
        } else {
            status.append(activity.getString(developerOptionsEnabled()
                    ? R.string.spoof_setup_select : R.string.spoof_setup_enable_dev));
        }
        if (running) {
            status.append('\n').append(activity.getString(R.string.spoof_current,
                    Format.coordinates(SpoofService.getCurrentLatitude(), SpoofService.getCurrentLongitude()),
                    String.join(", ", SpoofService.getActiveProviders())));
        }
        setupStatus.setText(status);
        setupStatus.setTextColor(activity.getColor(selected ? R.color.text_primary : R.color.state_warn));
        setupButton.setVisibility(selected && !running ? View.GONE : View.VISIBLE);
        startButton.setText(running ? R.string.spoof_apply : R.string.spoof_start);
        stopButton.setVisibility(running ? View.VISIBLE : View.GONE);
        String err = SpoofService.getLastError();
        error.setText(err != null ? err : "");
        error.setVisibility(err != null ? View.VISIBLE : View.GONE);
    }

    // ---- Actions ------------------------------------------------------------------------------

    private void useCurrent() {
        Location loc = monitor != null ? monitor.getLocation() : null;
        if (loc == null) {
            toast(R.string.toast_no_position);
            return;
        }
        coords.setText(Format.coordinates(loc.getLatitude(), loc.getLongitude()));
        if (loc.hasAltitude()) altitude.setText(String.format(Locale.ROOT, "%.0f", loc.getAltitude()));
    }

    private void pickWaypoint() {
        List<Waypoint> all = WaypointStore.get(activity).getAll();
        if (all.isEmpty()) {
            toast(R.string.spoof_no_waypoints);
            return;
        }
        String[] names = new String[all.size()];
        for (int i = 0; i < names.length; i++) names[i] = all.get(i).name;
        new AlertDialog.Builder(activity, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle(R.string.spoof_use_waypoint)
                .setItems(names, (d, which) -> {
                    Waypoint w = all.get(which);
                    coords.setText(Format.coordinates(w.latitude, w.longitude));
                    if (!Double.isNaN(w.altitude)) altitude.setText(String.format(Locale.ROOT, "%.0f", w.altitude));
                })
                .show();
    }

    private void start() {
        Location me = monitor != null ? monitor.getLocation() : null;
        double[] p = CoordinateParser.parse(coords.getText().toString(),
                me != null ? me.getLatitude() : Double.NaN, me != null ? me.getLongitude() : Double.NaN);
        if (p == null) {
            coords.setError(activity.getString(R.string.spoof_bad_coords));
            return;
        }
        if (!gps.isChecked() && !network.isChecked() && !(fused.isChecked() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)) {
            toast(R.string.spoof_err_no_provider);
            return;
        }
        if (!SpoofService.isMockAppSelected(activity)) {
            new AlertDialog.Builder(activity, android.R.style.Theme_Material_Dialog_Alert)
                    .setTitle(R.string.spoof_setup_title)
                    .setMessage(R.string.spoof_setup_message)
                    .setNegativeButton(android.R.string.cancel, null)
                    .setPositiveButton(R.string.spoof_open_dev, (d, w) -> openDeveloperOptions())
                    .show();
            return;
        }
        if (monitor == null || !monitor.hasPermission()) {
            toast(R.string.toast_need_permission);
            return;
        }
        float bearingDeg = (float) (((number(bearing, 0) % 360) + 360) % 360);
        SpoofService.Config c = new SpoofService.Config(p[0], p[1], number(altitude, 0),
                (float) Math.max(1, number(accuracy, 5)), (float) Math.max(0, number(speed, 0) / 3.6),
                bearingDeg, jitter.isChecked(), gps.isChecked(), network.isChecked(), fused.isChecked());
        savePrefs();
        SpoofService.start(activity, c);
    }

    private static double number(EditText e, double fallback) {
        try {
            String s = e.getText().toString().trim().replace(',', '.');
            double v = s.isEmpty() ? fallback : Double.parseDouble(s);
            return Double.isFinite(v) ? v : fallback;
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    private boolean developerOptionsEnabled() {
        return Settings.Global.getInt(activity.getContentResolver(),
                Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0) != 0;
    }

    /** Developer options when enabled, otherwise "About phone" (tap Build number 7 times). */
    private void openDeveloperOptions() {
        Intent intent = new Intent(developerOptionsEnabled()
                ? Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS : Settings.ACTION_DEVICE_INFO_SETTINGS);
        try {
            activity.startActivity(intent);
            toast(developerOptionsEnabled() ? R.string.spoof_toast_select : R.string.spoof_toast_enable_dev);
        } catch (ActivityNotFoundException e) {
            toast(R.string.toast_no_app);
        }
    }

    private void toast(int res) {
        Toast.makeText(activity, res, Toast.LENGTH_LONG).show();
    }

    // ---- Preferences --------------------------------------------------------------------------

    private SharedPreferences prefs() {
        return activity.getSharedPreferences(PREFS, Activity.MODE_PRIVATE);
    }

    private void loadPrefs() {
        SharedPreferences p = prefs();
        coords.setText(p.getString("coords", ""));
        altitude.setText(p.getString("altitude", ""));
        accuracy.setText(p.getString("accuracy", "5"));
        speed.setText(p.getString("speed", "0"));
        bearing.setText(p.getString("bearing", "0"));
        jitter.setChecked(p.getBoolean("jitter", true));
        gps.setChecked(p.getBoolean("gps", true));
        network.setChecked(p.getBoolean("network", true));
        fused.setChecked(p.getBoolean("fused", true));
    }

    private void savePrefs() {
        prefs().edit()
                .putString("coords", coords.getText().toString())
                .putString("altitude", altitude.getText().toString())
                .putString("accuracy", accuracy.getText().toString())
                .putString("speed", speed.getText().toString())
                .putString("bearing", bearing.getText().toString())
                .putBoolean("jitter", jitter.isChecked())
                .putBoolean("gps", gps.isChecked())
                .putBoolean("network", network.isChecked())
                .putBoolean("fused", fused.isChecked())
                .apply();
    }
}

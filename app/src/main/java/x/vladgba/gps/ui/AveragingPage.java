package x.vladgba.gps.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.graphics.Typeface;
import android.os.Build;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import x.vladgba.gps.R;
import x.vladgba.gps.coords.BritishGrid;
import x.vladgba.gps.coords.Mgrs;
import x.vladgba.gps.coords.PlusCode;
import x.vladgba.gps.coords.Utm;
import x.vladgba.gps.coords.VoiceCode;
import x.vladgba.gps.gnss.GnssMonitor;
import x.vladgba.gps.gnss.PositionAverager;
import x.vladgba.gps.nav.WaypointStore;

import java.util.Locale;

/** Survey mode: average a stationary position and show its spread. */
final class AveragingPage implements Page {

    private static final float[] FILTERS = {Float.POSITIVE_INFINITY, 20f, 10f, 5f};

    private final Activity activity;
    private final ScrollView root;
    private final Button startPause;
    private final TextView[] filterChips = new TextView[FILTERS.length];
    private final TextView stats;
    private final TextView staticHold;
    private final ScatterPlotView scatter;
    private GnssMonitor monitor;

    AveragingPage(Activity activity) {
        this.activity = activity;
        root = new ScrollView(activity);
        LinearLayout list = new LinearLayout(activity);
        list.setOrientation(LinearLayout.VERTICAL);
        int pad = Ui.dp(activity, 16);
        list.setPadding(pad, pad, pad, pad);
        root.addView(list);

        LinearLayout buttons = new LinearLayout(activity);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        startPause = new Button(activity);
        startPause.setOnClickListener(v -> toggle());
        buttons.addView(startPause, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        Button reset = new Button(activity);
        reset.setText(R.string.avg_reset);
        reset.setOnClickListener(v -> {
            if (monitor == null) return;
            monitor.getAverager().reset();
            update(monitor);
        });
        buttons.addView(reset, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        list.addView(buttons);

        LinearLayout filters = new LinearLayout(activity);
        filters.setOrientation(LinearLayout.HORIZONTAL);
        filters.setGravity(Gravity.CENTER_VERTICAL);
        filters.setPadding(0, Ui.dp(activity, 8), 0, Ui.dp(activity, 8));
        TextView label = new TextView(activity);
        label.setText(R.string.avg_filter);
        label.setTextColor(activity.getColor(R.color.text_secondary));
        label.setTextSize(13);
        filters.addView(label);
        for (int i = 0; i < FILTERS.length; i++) {
            final int index = i;
            TextView chip = new TextView(activity, null, 0, R.style.SortChip);
            chip.setText(Float.isInfinite(FILTERS[i]) ? activity.getString(R.string.avg_any)
                    : String.format(Locale.ROOT, "≤ %.0f m", FILTERS[i]));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, Ui.dp(activity, 32));
            lp.setMarginStart(Ui.dp(activity, 6));
            chip.setOnClickListener(v -> {
                if (monitor == null) return;
                monitor.getAverager().setMaxAccuracy(FILTERS[index]);
                update(monitor);
            });
            filterChips[i] = chip;
            filters.addView(chip, lp);
        }
        list.addView(filters);

        scatter = new ScatterPlotView(activity);
        list.addView(scatter);

        stats = new TextView(activity);
        stats.setTypeface(Typeface.MONOSPACE);
        stats.setTextSize(13);
        stats.setTextColor(activity.getColor(R.color.text_primary));
        stats.setTextIsSelectable(true);
        stats.setPadding(0, Ui.dp(activity, 8), 0, Ui.dp(activity, 8));
        list.addView(stats);

        staticHold = new TextView(activity);
        staticHold.setText(R.string.avg_static_hold);
        staticHold.setTextSize(13);
        staticHold.setTextColor(activity.getColor(R.color.state_warn));
        staticHold.setVisibility(View.GONE);
        list.addView(staticHold);

        LinearLayout actions = new LinearLayout(activity);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        Button copy = new Button(activity, null, android.R.attr.borderlessButtonStyle);
        copy.setText(R.string.avg_copy);
        copy.setTextColor(activity.getColor(R.color.accent));
        copy.setOnClickListener(v -> copyMean());
        actions.addView(copy);
        Button save = new Button(activity, null, android.R.attr.borderlessButtonStyle);
        save.setText(R.string.avg_save);
        save.setTextColor(activity.getColor(R.color.accent));
        save.setOnClickListener(v -> saveAsWaypoint());
        actions.addView(save);
        list.addView(actions);

        TextView hint = new TextView(activity);
        hint.setText(R.string.avg_hint);
        hint.setTextSize(12);
        hint.setTextColor(activity.getColor(R.color.text_secondary));
        list.addView(hint);
    }

    private void toggle() {
        if (monitor == null) return;
        PositionAverager a = monitor.getAverager();
        if (a.isRunning()) a.pause(SystemClock.elapsedRealtime());
        else a.start(SystemClock.elapsedRealtime());
        update(monitor);
    }

    @Override
    public View getView() {
        return root;
    }

    @Override
    public void update(GnssMonitor m) {
        monitor = m;
        PositionAverager a = m.getAverager();
        startPause.setText(a.isRunning() ? R.string.avg_pause : R.string.avg_start);
        for (int i = 0; i < FILTERS.length; i++) filterChips[i].setSelected(FILTERS[i] == a.getMaxAccuracy());
        scatter.setAverager(a);

        StringBuilder sb = new StringBuilder();
        sb.append(String.format(Locale.ROOT, "Samples   %d (rejected %d)%n", a.getCount(), a.getRejected()));
        sb.append(String.format(Locale.ROOT, "Duration  %s%n", Format.duration(a.getDurationMs(SystemClock.elapsedRealtime()))));
        if (a.hasResult()) {
            sb.append(String.format(Locale.ROOT, "Latitude  %.8f°  %s%n", a.getMeanLatitude(), Format.dms(a.getMeanLatitude(), true)));
            sb.append(String.format(Locale.ROOT, "Longitude %.8f°  %s%n", a.getMeanLongitude(), Format.dms(a.getMeanLongitude(), false)));
            String mgrs = Mgrs.format(a.getMeanLatitude(), a.getMeanLongitude(), 5);
            if (mgrs != null) sb.append(String.format(Locale.ROOT, "MGRS      %s%n", mgrs));
            Utm utm = Utm.from(a.getMeanLatitude(), a.getMeanLongitude());
            if (utm != null) sb.append(String.format(Locale.ROOT, "UTM       %s%n", utm.format()));
            BritishGrid bng = BritishGrid.fromWgs84(a.getMeanLatitude(), a.getMeanLongitude());
            if (bng != null) sb.append(String.format(Locale.ROOT, "OSGB      %s%n", bng.gridReference()));
            sb.append(String.format(Locale.ROOT, "Plus Code %s%n", PlusCode.encode(a.getMeanLatitude(), a.getMeanLongitude(), 11)));
            sb.append(String.format(Locale.ROOT, "Voice     %s%n", VoiceCode.encode(a.getMeanLatitude(), a.getMeanLongitude(), VoiceCode.Tier.GLOBAL)));
            sb.append(String.format(Locale.ROOT, "Altitude  %s (WGS84)%n", Format.meters(a.getMeanAltitude())));
            sb.append(String.format(Locale.ROOT, "σ E/N/U   %s / %s / %s%n", m2(a.getStdEast()), m2(a.getStdNorth()), m2(a.getStdUp())));
            sb.append(String.format(Locale.ROOT, "CEP50     %s   (50%% of fixes)%n", m2(a.getCep50())));
            sb.append(String.format(Locale.ROOT, "2DRMS     %s   (~95%% of fixes)", m2(a.get2Drms())));
        }
        stats.setText(sb);
        staticHold.setVisibility(a.isStaticHoldSuspected() ? View.VISIBLE : View.GONE);
    }

    private static String m2(double v) {
        return Double.isNaN(v) ? Format.NONE : String.format(Locale.ROOT, "%.2f m", v);
    }

    private void copyMean() {
        if (monitor == null || !monitor.getAverager().hasResult()) {
            Toast.makeText(activity, R.string.toast_no_position, Toast.LENGTH_SHORT).show();
            return;
        }
        PositionAverager a = monitor.getAverager();
        ClipboardManager cm = activity.getSystemService(ClipboardManager.class);
        cm.setPrimaryClip(ClipData.newPlainText("position", Format.coordinates(a.getMeanLatitude(), a.getMeanLongitude())));
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            Toast.makeText(activity, R.string.toast_copied, Toast.LENGTH_SHORT).show();
        }
    }

    private void saveAsWaypoint() {
        if (monitor == null || !monitor.getAverager().hasResult()) {
            Toast.makeText(activity, R.string.toast_no_position, Toast.LENGTH_SHORT).show();
            return;
        }
        PositionAverager a = monitor.getAverager();
        WaypointStore store = WaypointStore.get(activity);
        EditText name = new EditText(activity);
        name.setText(activity.getString(R.string.wp_default_name, store.getAll().size() + 1));
        name.selectAll();
        new AlertDialog.Builder(activity, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle(R.string.avg_save)
                .setView(WaypointsPage.padded(activity, name))
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(android.R.string.ok, (d, w) -> {
                    store.add(name.getText().toString().trim(), a.getMeanLatitude(), a.getMeanLongitude(), a.getMeanAltitude());
                    Toast.makeText(activity, R.string.avg_saved, Toast.LENGTH_SHORT).show();
                })
                .show();
    }
}

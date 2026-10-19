package com.vladgba.gpstools.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.vladgba.gpstools.R;
import com.vladgba.gpstools.gnss.Constellations;
import com.vladgba.gpstools.gnss.GnssMonitor;
import com.vladgba.gpstools.gnss.QualityHistory;
import com.vladgba.gpstools.gnss.SatelliteInfo;

/** Fix quality over the last minutes: accuracy, DOP, satellite counts and signal strength. */
final class HistoryPage implements Page {

    private static final long[] WINDOWS = {5 * 60_000L, 15 * 60_000L, 30 * 60_000L};
    private static final int BLUE = 0xFF4FC3F7;
    private static final int ORANGE = 0xFFFFA726;
    private static final int GREEN = 0xFF66BB6A;
    private static final int PURPLE = 0xFFAB47BC;

    private final Activity activity;
    private final ScrollView root;
    private final LinearLayout charts;
    private final TextView[] windowChips = new TextView[WINDOWS.length];
    private LineChartView accuracy;
    private LineChartView dop;
    private LineChartView satellites;
    private LineChartView signal;
    private long window = WINDOWS[1];

    HistoryPage(Activity activity) {
        this.activity = activity;
        root = new ScrollView(activity);
        LinearLayout list = new LinearLayout(activity);
        list.setOrientation(LinearLayout.VERTICAL);
        int pad = Ui.dp(activity, 12);
        list.setPadding(pad, pad, pad, pad);
        root.addView(list);

        LinearLayout chips = new LinearLayout(activity);
        chips.setOrientation(LinearLayout.HORIZONTAL);
        chips.setGravity(Gravity.CENTER_VERTICAL);
        for (int i = 0; i < WINDOWS.length; i++) {
            final int index = i;
            TextView chip = new TextView(activity, null, 0, R.style.SortChip);
            chip.setText(activity.getString(R.string.history_window, (int) (WINDOWS[i] / 60_000)));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, Ui.dp(activity, 32));
            lp.setMarginEnd(Ui.dp(activity, 8));
            chip.setOnClickListener(v -> setWindow(WINDOWS[index]));
            windowChips[i] = chip;
            chips.addView(chip, lp);
        }
        list.addView(chips);
        charts = list;
    }

    private void build(QualityHistory h) {
        accuracy = chart(R.string.history_accuracy).addSeries(h.horizontalAccuracy, BLUE, s(R.string.history_horizontal))
                .addSeries(h.verticalAccuracy, ORANGE, s(R.string.history_vertical)).setAutoRange(5, true);
        dop = chart(R.string.history_dop).addSeries(h.hdop, GREEN, "HDOP").addSeries(h.pdop, PURPLE, "PDOP")
                .setAutoRange(2, true);
        satellites = chart(R.string.history_satellites).addSeries(h.satellitesUsed, GREEN, s(R.string.history_used))
                .addSeries(h.satellitesInView, BLUE, s(R.string.history_in_view)).setAutoRange(5, true);
        signal = chart(R.string.history_signal).addSeries(h.topCn0, BLUE, s(R.string.history_top4))
                .addSeries(h.meanCn0, ORANGE, s(R.string.history_mean)).setRange(0, 60);
        setWindow(window);
    }

    private LineChartView chart(int titleRes) {
        LineChartView c = new LineChartView(activity).setTitle(s(titleRes));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, Ui.dp(activity, 170));
        lp.topMargin = Ui.dp(activity, 16);
        charts.addView(c, lp);
        return c;
    }

    private void setWindow(long ms) {
        window = ms;
        for (int i = 0; i < WINDOWS.length; i++) windowChips[i].setSelected(WINDOWS[i] == ms);
        if (accuracy == null) return;
        for (LineChartView c : new LineChartView[]{accuracy, dop, satellites, signal}) c.setWindow(ms);
    }

    private String s(int res) {
        return activity.getString(res);
    }

    @Override
    public View getView() {
        return root;
    }

    @Override
    public void update(GnssMonitor m) {
        if (accuracy == null) build(m.getHistory());
        for (LineChartView c : new LineChartView[]{accuracy, dop, satellites, signal}) c.invalidate();
    }

    /** Dialog with one satellite's C/N0 and elevation over the last 30 minutes, live. */
    static void showSatellite(Activity activity, GnssMonitor monitor, SatelliteInfo s) {
        QualityHistory.SatelliteHistory h = monitor.getHistory().satellite(s.key());
        AlertDialog.Builder b = new AlertDialog.Builder(activity, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle(activity.getString(R.string.history_satellite_title,
                        s.rinexId() + " " + Constellations.name(s.constellation)))
                .setPositiveButton(android.R.string.ok, null);
        if (h == null) {
            b.setMessage(R.string.history_no_data).show();
            return;
        }
        LinearLayout box = new LinearLayout(activity);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = Ui.dp(activity, 16);
        box.setPadding(pad, pad, pad, 0);
        int color = Constellations.color(s.constellation);
        LineChartView cn0 = new LineChartView(activity).setTitle(activity.getString(R.string.history_cn0))
                .addSeries(h.cn0, color, "C/N0").setRange(0, 60);
        LineChartView elevation = new LineChartView(activity).setTitle(activity.getString(R.string.history_elevation))
                .addSeries(h.elevation, color, "elevation").setRange(0, 90);
        cn0.setWindow(QualityHistory.WINDOW_MS);
        elevation.setWindow(QualityHistory.WINDOW_MS);
        box.addView(cn0, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, Ui.dp(activity, 160)));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, Ui.dp(activity, 160));
        lp.topMargin = Ui.dp(activity, 12);
        box.addView(elevation, lp);
        AlertDialog dialog = b.setView(box).create();
        Handler handler = new Handler(Looper.getMainLooper());
        Runnable refresh = new Runnable() {
            @Override
            public void run() {
                if (!dialog.isShowing()) return;
                cn0.invalidate();
                elevation.invalidate();
                handler.postDelayed(this, 1000);
            }
        };
        dialog.setOnShowListener(d -> handler.post(refresh));
        dialog.setOnDismissListener(d -> handler.removeCallbacks(refresh));
        dialog.show();
    }
}

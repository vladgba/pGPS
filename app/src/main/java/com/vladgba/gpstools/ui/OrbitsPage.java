package com.vladgba.gpstools.ui;

import android.app.Activity;
import android.graphics.Typeface;
import android.location.Location;
import android.os.Handler;
import android.os.Looper;
import android.text.format.DateUtils;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.vladgba.gpstools.R;
import com.vladgba.gpstools.gnss.Constellations;
import com.vladgba.gpstools.gnss.GnssMonitor;
import com.vladgba.gpstools.orbit.OrbitCatalog;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** Orbit data status, 12 h visibility forecast and the upcoming rises/sets. */
final class OrbitsPage implements Page, OrbitCatalog.Callback {

    private static final long FORECAST_STEP_MS = 10 * 60_000L;
    private static final int FORECAST_STEPS = 73; // 12 h
    private static final long PASSES_SPAN_MS = 2 * 3600_000L;
    private static final float MASK_DEG = 10f;
    private static final int MAX_PASSES = 40;
    private static final long RECOMPUTE_MS = 60_000L;

    private final Activity activity;
    private final OrbitCatalog catalog;
    private final ScrollView root;
    private final TextView status;
    private final Button updateButton;
    private final ForecastView forecast;
    private final LinearLayout passes;
    private final Handler main = new Handler(Looper.getMainLooper());
    private long computedAtMs;
    private boolean computing;
    private GnssMonitor monitor;

    OrbitsPage(Activity activity) {
        this.activity = activity;
        catalog = OrbitCatalog.get(activity);
        root = new ScrollView(activity);
        LinearLayout list = new LinearLayout(activity);
        list.setOrientation(LinearLayout.VERTICAL);
        int pad = Ui.dp(activity, 16);
        list.setPadding(pad, pad, pad, pad);
        root.addView(list);

        status = new TextView(activity);
        status.setTextSize(13);
        status.setTextColor(activity.getColor(R.color.text_secondary));
        list.addView(status);
        updateButton = new Button(activity, null, android.R.attr.borderlessButtonStyle);
        updateButton.setText(R.string.orbits_update);
        updateButton.setTextColor(activity.getColor(R.color.accent));
        updateButton.setOnClickListener(v -> catalog.refresh());
        list.addView(updateButton);

        list.addView(section(R.string.orbits_forecast));
        forecast = new ForecastView(activity);
        list.addView(forecast, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, Ui.dp(activity, 190)));

        list.addView(section(R.string.orbits_passes));
        passes = new LinearLayout(activity);
        passes.setOrientation(LinearLayout.VERTICAL);
        list.addView(passes);

        TextView disclaimer = new TextView(activity);
        disclaimer.setText(R.string.orbits_disclaimer);
        disclaimer.setTextSize(12);
        disclaimer.setTextColor(activity.getColor(R.color.text_secondary));
        disclaimer.setPadding(0, Ui.dp(activity, 16), 0, 0);
        list.addView(disclaimer);

        catalog.addCallback(this);
    }

    @Override
    public View getView() {
        return root;
    }

    @Override
    public void update(GnssMonitor m) {
        boolean first = monitor == null;
        monitor = m;
        if (first) catalog.refreshIfStale();
        renderStatus();
        Location loc = m.getLocation();
        if (loc != null && !catalog.isEmpty()) {
            // Keep the GLONASS/Galileo identification fresh for the labels.
            catalog.matchObserved(catalog.positions(System.currentTimeMillis(), loc.getLatitude(),
                    loc.getLongitude(), loc.getAltitude()), m.getSatellites());
            if (System.currentTimeMillis() - computedAtMs > RECOMPUTE_MS) compute(loc);
        }
    }

    @Override
    public void onCatalogChanged(OrbitCatalog c, String error) {
        computedAtMs = 0;
        renderStatus();
        if (monitor != null && monitor.getLocation() != null && !catalog.isEmpty()) compute(monitor.getLocation());
    }

    private void renderStatus() {
        String text;
        if (catalog.isLoading()) {
            text = activity.getString(R.string.orbits_loading);
        } else if (catalog.isEmpty()) {
            text = activity.getString(R.string.orbits_none);
        } else {
            text = activity.getString(R.string.orbits_status, catalog.getSatellites().size(),
                    DateUtils.getRelativeTimeSpanString(catalog.getFetchedAtMs()));
        }
        if (catalog.getLastError() != null) text += "\n" + activity.getString(R.string.orbits_error, catalog.getLastError());
        if (monitor != null && monitor.getLocation() == null) text += "\n" + activity.getString(R.string.orbits_need_position);
        status.setText(text);
        updateButton.setEnabled(!catalog.isLoading());
    }

    /** Forecast and passes are ~25k propagations: computed off the main thread. */
    private void compute(Location loc) {
        if (computing) return;
        computing = true;
        computedAtMs = System.currentTimeMillis();
        final double lat = loc.getLatitude();
        final double lon = loc.getLongitude();
        final double alt = loc.getAltitude();
        final long now = System.currentTimeMillis();
        new Thread(() -> {
            int[][] counts = catalog.visibilityForecast(now, FORECAST_STEP_MS, FORECAST_STEPS, MASK_DEG, lat, lon, alt);
            List<OrbitCatalog.Pass> list = catalog.passes(now, PASSES_SPAN_MS, MASK_DEG, lat, lon, alt);
            main.post(() -> {
                computing = false;
                forecast.setForecast(counts, now, FORECAST_STEP_MS);
                renderPasses(list);
            });
        }, "orbit-forecast").start();
    }

    private void renderPasses(List<OrbitCatalog.Pass> list) {
        passes.removeAllViews();
        if (list.isEmpty()) {
            passes.addView(line(activity.getString(R.string.orbits_no_passes), R.color.text_secondary));
            return;
        }
        SimpleDateFormat time = new SimpleDateFormat("HH:mm", Locale.getDefault());
        for (int i = 0; i < Math.min(MAX_PASSES, list.size()); i++) {
            OrbitCatalog.Pass p = list.get(i);
            OrbitCatalog.Satellite s = p.satellite;
            String id = s.svid > 0 ? Constellations.rinexId(s.constellation, s.svid) : Constellations.name(s.constellation);
            TextView t = line(String.format(Locale.ROOT, "%s  %s %-4s %-26.26s az %3.0f°",
                    time.format(new Date(p.timeMs)), p.rising ? "↑" : "↓", id, s.tle.name, p.azimuth), R.color.text_primary);
            t.setTextColor(p.rising ? Constellations.color(s.constellation) : activity.getColor(R.color.text_secondary));
            passes.addView(t);
        }
    }

    private TextView line(String text, int color) {
        TextView t = new TextView(activity);
        t.setText(text);
        t.setTypeface(Typeface.MONOSPACE);
        t.setTextSize(12);
        t.setTextColor(activity.getColor(color));
        t.setPadding(0, Ui.dp(activity, 2), 0, Ui.dp(activity, 2));
        return t;
    }

    private TextView section(int res) {
        TextView t = new TextView(activity);
        t.setText(res);
        t.setTextSize(12);
        t.setTextColor(activity.getColor(R.color.accent));
        t.setAllCaps(true);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setLetterSpacing(0.08f);
        t.setPadding(0, Ui.dp(activity, 16), 0, Ui.dp(activity, 6));
        return t;
    }
}

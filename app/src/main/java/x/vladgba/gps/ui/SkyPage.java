package x.vladgba.gps.ui;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.location.Location;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import x.vladgba.gps.R;
import x.vladgba.gps.gnss.ConstellationCount;
import x.vladgba.gps.gnss.Constellations;
import x.vladgba.gps.gnss.GnssMonitor;

import x.vladgba.gps.nav.WaypointStore;
import x.vladgba.gps.orbit.OrbitCatalog;

import java.util.List;
import java.util.Locale;

/**
 * Sky tab: the 2D polar plot with signal bars, or the first-person 3D sky view, optionally as an
 * AR overlay on the back camera.
 */
public final class SkyPage implements Page {

    public static final int REQUEST_CAMERA = 5;

    private final Context context;
    private final LinearLayout root;
    private final View plotMode;
    private final Sky3DView sky3d;
    private final SkyPlotView skyPlot;
    private final TextView legend;
    private final SignalBarsView signalBars;
    private final TextView plotChip;
    private final TextView threeDChip;
    private final TextView compassChip;
    private final TextView orbitsChip;
    private final TextView arChip;
    private final ArCameraView camera;
    private final OrbitCatalog catalog;
    private final WaypointStore waypoints;
    private boolean threeD;

    public SkyPage(Context context) {
        this.context = context;
        catalog = OrbitCatalog.get(context);
        waypoints = WaypointStore.get(context);
        root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);

        LinearLayout chips = new LinearLayout(context);
        chips.setOrientation(LinearLayout.HORIZONTAL);
        chips.setGravity(Gravity.CENTER_VERTICAL);
        chips.setPadding(Ui.dp(context, 8), Ui.dp(context, 8), Ui.dp(context, 16), Ui.dp(context, 8));
        plotChip = chip(R.string.sky_mode_plot);
        threeDChip = chip(R.string.sky_mode_3d);
        compassChip = chip(R.string.sky_compass);
        orbitsChip = chip(R.string.sky_orbits);
        arChip = chip(R.string.sky_ar);
        chips.addView(plotChip);
        chips.addView(threeDChip);
        View spacer = new View(context);
        chips.addView(spacer, new LinearLayout.LayoutParams(0, 1, 1f));
        chips.addView(orbitsChip);
        chips.addView(compassChip);
        chips.addView(arChip);
        root.addView(chips);

        FrameLayout body = new FrameLayout(context);
        root.addView(body, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        // 2D plot mode
        LinearLayout plot = new LinearLayout(context);
        plot.setOrientation(LinearLayout.VERTICAL);
        int pad = Ui.dp(context, 8);
        plot.setPadding(pad, 0, pad, pad);
        skyPlot = new SkyPlotView(context);
        plot.addView(skyPlot, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        legend = new TextView(context);
        legend.setGravity(Gravity.CENTER);
        legend.setTextSize(13);
        legend.setTextColor(context.getColor(R.color.text_primary));
        legend.setPadding(0, Ui.dp(context, 6), 0, Ui.dp(context, 6));
        plot.addView(legend, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        signalBars = new SignalBarsView(context);
        plot.addView(signalBars, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, Ui.dp(context, 140)));
        plotMode = plot;
        body.addView(plot);

        // 3D mode: the camera preview (AR only) sits under the transparent sky overlay.
        camera = new ArCameraView(context);
        camera.setVisibility(View.GONE);
        camera.setListener(new ArCameraView.Listener() {
            @Override
            public void onFocalChanged(double focalPx) {
                sky3d.setArFocalPx(focalPx);
            }

            @Override
            public void onCameraError(String message) {
                Toast.makeText(context, context.getString(R.string.sky_ar_camera_error, message), Toast.LENGTH_SHORT).show();
                setAr(false);
            }
        });
        body.addView(camera);
        sky3d = new Sky3DView(context);
        sky3d.setListener(follow -> compassChip.setSelected(follow));
        body.addView(sky3d);

        plotChip.setOnClickListener(v -> setThreeD(false));
        threeDChip.setOnClickListener(v -> setThreeD(true));
        compassChip.setOnClickListener(v -> {
            sky3d.setFollowSensor(!sky3d.isFollowSensor());
            compassChip.setSelected(sky3d.isFollowSensor());
        });
        orbitsChip.setOnClickListener(v -> {
            sky3d.setShowPredictions(!sky3d.isShowPredictions());
            orbitsChip.setSelected(sky3d.isShowPredictions());
            if (sky3d.isShowPredictions()) catalog.refreshIfStale();
        });
        orbitsChip.setSelected(sky3d.isShowPredictions());
        arChip.setOnClickListener(v -> {
            if (sky3d.isArMode()) setAr(false);
            else requestAr();
        });
        setThreeD(false);
    }

    private boolean arSupported() {
        return camera.isAvailable() && sky3d.hasOrientationSensor();
    }

    private void requestAr() {
        if (camera.hasPermission()) {
            setAr(true);
        } else if (context instanceof Activity) {
            ((Activity) context).requestPermissions(new String[]{Manifest.permission.CAMERA}, REQUEST_CAMERA);
        }
    }

    @Override
    public void onPermissionResult(int requestCode, boolean granted) {
        if (requestCode != REQUEST_CAMERA) return;
        if (granted) {
            if (root.isAttachedToWindow() && root.isShown()) setAr(true);
        } else Toast.makeText(context, R.string.sky_ar_no_permission, Toast.LENGTH_LONG).show();
    }

    private void setAr(boolean on) {
        on = on && threeD && arSupported();
        sky3d.setArMode(on);
        arChip.setSelected(on);
        camera.setVisibility(on ? View.VISIBLE : View.GONE);
        camera.setActive(on);
        if (on && !Double.isNaN(camera.getFocalPx())) sky3d.setArFocalPx(camera.getFocalPx());
        // AR forces the compass on; the chip would only confuse.
        compassChip.setVisibility(threeD && !on && sky3d.hasOrientationSensor() ? View.VISIBLE : View.GONE);
        compassChip.setSelected(sky3d.isFollowSensor());
    }

    private void setThreeD(boolean value) {
        threeD = value;
        plotChip.setSelected(!value);
        threeDChip.setSelected(value);
        plotMode.setVisibility(value ? View.GONE : View.VISIBLE);
        sky3d.setVisibility(value ? View.VISIBLE : View.GONE);
        compassChip.setVisibility(value && sky3d.hasOrientationSensor() ? View.VISIBLE : View.GONE);
        orbitsChip.setVisibility(value ? View.VISIBLE : View.GONE);
        arChip.setVisibility(value && arSupported() ? View.VISIBLE : View.GONE);
        if (value && sky3d.isShowPredictions()) catalog.refreshIfStale();
        if (!value) {
            // Leaving 3D also stops the camera and the orientation sensor.
            setAr(false);
            sky3d.setFollowSensor(false);
            compassChip.setSelected(false);
        }
    }

    private TextView chip(int textRes) {
        TextView chip = new TextView(context, null, 0, R.style.SortChip);
        chip.setText(textRes);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, Ui.dp(context, 32));
        lp.setMarginStart(Ui.dp(context, 8));
        chip.setLayoutParams(lp);
        return chip;
    }

    @Override
    public View getView() {
        return root;
    }

    @Override
    public void update(GnssMonitor m) {
        // Trails accumulate in both modes so switching to 3D shows the recent motion.
        sky3d.setSatellites(m.getSatellites());
        Location loc = m.getLocation();
        if (loc != null) sky3d.setObserver(loc.getLatitude(), loc.getLongitude(), loc.getAltitude(), loc.getTime());
        if (threeD) {
            sky3d.setTarget(waypoints.getTarget());
            if (loc != null && sky3d.isShowPredictions() && !catalog.isEmpty()) {
                List<OrbitCatalog.Position> positions = catalog.positions(System.currentTimeMillis(),
                        loc.getLatitude(), loc.getLongitude(), loc.getAltitude());
                catalog.matchObserved(positions, m.getSatellites());
                sky3d.setPredictions(positions, catalog);
            }
            return;
        }

        String empty = context.getString(m.isRunning() ? R.string.waiting_satellites : R.string.state_stopped);
        skyPlot.setSatellites(m.getSatellites(), empty);
        signalBars.setSatellites(m.getSatellites());

        SpannableStringBuilder text = new SpannableStringBuilder();
        for (ConstellationCount c : m.getConstellationCounts()) {
            if (text.length() > 0) text.append("   ");
            int start = text.length();
            text.append("●");
            text.setSpan(new ForegroundColorSpan(Constellations.color(c.constellation)),
                    start, text.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            text.append(String.format(Locale.ROOT, " %s %d/%d",
                    Constellations.name(c.constellation), c.used, c.inView));
        }
        legend.setText(text);
        legend.setVisibility(text.length() > 0 ? View.VISIBLE : View.GONE);
    }
}

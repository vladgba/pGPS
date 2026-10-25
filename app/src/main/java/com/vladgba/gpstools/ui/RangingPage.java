package com.vladgba.gpstools.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.graphics.Typeface;
import android.location.Location;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.vladgba.gpstools.R;
import com.vladgba.gpstools.coords.CoordinateParser;
import com.vladgba.gpstools.coords.Mgrs;
import com.vladgba.gpstools.coords.TextCode;
import com.vladgba.gpstools.gnss.GnssMonitor;
import com.vladgba.gpstools.nav.Geodesic;
import com.vladgba.gpstools.nav.Waypoint;
import com.vladgba.gpstools.nav.WaypointStore;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Range & Triangulation tool:
 * 1. Flash-to-bang acoustic ranging (stopwatch + speed of sound + azimuth target projection).
 * 2. Point-to-point distance, forward/back bearing, and altitude delta between any two points.
 * 3. Multi-bearing triangulation (2+ rays from GPS or waypoints) with residual spread and map plot.
 */
final class RangingPage implements Page, WaypointStore.Listener {

    private static final int MODE_FLASH = 0;
    private static final int MODE_POINTS = 1;
    private static final int MODE_TRIANGULATE = 2;

    private final Activity activity;
    private final WaypointStore store;
    private final OrientationTracker orientation;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ScrollView root;
    private final TextView[] modeChips = new TextView[3];
    private final TextView liveCompassText;
    private final LinearLayout flashSection;
    private final LinearLayout pointsSection;
    private final LinearLayout triSection;
    private final RangingMapView map;
    private GnssMonitor monitor;
    private int mode = MODE_FLASH;
    private boolean visible;

    // ---- Flash-to-bang state ----
    private boolean timerRunning;
    private long flashStartRealtimeMs;
    private final Button flashTriggerBtn;
    private final Button flashResetBtn;
    private final EditText elapsedInput;
    private final EditText azimuthInput;
    private final EditText tempInput;
    private final TextView soundInfoText;
    private final TextView flashOriginLabel;
    private final TextView flashResultText;
    private final LinearLayout flashTargetActions;
    private boolean suppressTextWatchers;
    private boolean flashOriginUseGps = true;
    private double flashOriginLat = Double.NaN;
    private double flashOriginLon = Double.NaN;
    private String flashOriginCustomName = "";
    private double[] flashProjectedTarget;

    private final Runnable timerTick = new Runnable() {
        @Override
        public void run() {
            if (!timerRunning) return;
            double sec = (SystemClock.elapsedRealtime() - flashStartRealtimeMs) / 1000.0;
            suppressTextWatchers = true;
            elapsedInput.setText(String.format(Locale.ROOT, "%.2f", sec));
            suppressTextWatchers = false;
            computeFlashToBang();
            handler.postDelayed(this, 50);
        }
    };

    // ---- Two-points state ----
    private boolean pointAUseGps = true;
    private double pointALat = Double.NaN;
    private double pointALon = Double.NaN;
    private double pointAAlt = Double.NaN;
    private String pointAName = "";

    private boolean pointBUseGps = false;
    private double pointBLat = Double.NaN;
    private double pointBLon = Double.NaN;
    private double pointBAlt = Double.NaN;
    private String pointBName = "";

    private final TextView pointASummary;
    private final TextView pointBSummary;
    private final TextView twoPointsResult;

    // ---- Triangulation state ----
    private final List<Geodesic.Ray> rays = new ArrayList<>();
    private final LinearLayout raysListLayout;
    private final TextView triResultText;
    private final LinearLayout triTargetActions;
    private Geodesic.Intersection currentIntersection;

    RangingPage(Activity activity) {
        this.activity = activity;
        this.store = WaypointStore.get(activity);
        this.orientation = new OrientationTracker(activity, this::onCompassUpdated);

        root = new ScrollView(activity);
        LinearLayout content = new LinearLayout(activity);
        content.setOrientation(LinearLayout.VERTICAL);
        int pad = Ui.dp(activity, 16);
        content.setPadding(pad, pad, pad, pad);
        root.addView(content);

        // Mode selector chips
        LinearLayout chipsRow = new LinearLayout(activity);
        chipsRow.setOrientation(LinearLayout.HORIZONTAL);
        chipsRow.setGravity(Gravity.CENTER_VERTICAL);
        int[] modeTitles = {R.string.rng_mode_flash, R.string.rng_mode_points, R.string.rng_mode_triangulate};
        for (int i = 0; i < 3; i++) {
            final int m = i;
            TextView chip = new TextView(activity, null, 0, R.style.SortChip);
            chip.setText(modeTitles[i]);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, Ui.dp(activity, 34));
            if (i > 0) lp.setMarginStart(Ui.dp(activity, 8));
            chip.setLayoutParams(lp);
            chip.setOnClickListener(v -> setMode(m));
            modeChips[i] = chip;
            chipsRow.addView(chip);
        }
        content.addView(chipsRow);

        // Live compass readout bar
        LinearLayout compassBar = new LinearLayout(activity);
        compassBar.setOrientation(LinearLayout.HORIZONTAL);
        compassBar.setGravity(Gravity.CENTER_VERTICAL);
        compassBar.setPadding(0, Ui.dp(activity, 8), 0, Ui.dp(activity, 4));
        liveCompassText = text(13, R.color.accent);
        liveCompassText.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        liveCompassText.setText(R.string.rng_live_heading_none);
        compassBar.addView(liveCompassText, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        Button lockCompassBtn = borderless(R.string.rng_use_compass, v -> lockLiveCompassToAzimuth());
        compassBar.addView(lockCompassBtn);
        content.addView(compassBar);

        // ==================== 1. FLASH-TO-BANG SECTION ====================
        flashSection = new LinearLayout(activity);
        flashSection.setOrientation(LinearLayout.VERTICAL);

        LinearLayout timerBtnRow = new LinearLayout(activity);
        timerBtnRow.setOrientation(LinearLayout.HORIZONTAL);
        flashTriggerBtn = new Button(activity);
        flashTriggerBtn.setText(R.string.rng_flash_start);
        flashTriggerBtn.setTextColor(activity.getColor(R.color.state_good));
        flashTriggerBtn.setOnClickListener(v -> toggleFlashTimer());
        timerBtnRow.addView(flashTriggerBtn, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        flashResetBtn = new Button(activity);
        flashResetBtn.setText(R.string.rng_flash_reset);
        flashResetBtn.setOnClickListener(v -> resetFlashTimer());
        LinearLayout.LayoutParams resetLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        resetLp.setMarginStart(Ui.dp(activity, 8));
        timerBtnRow.addView(flashResetBtn, resetLp);
        flashSection.addView(timerBtnRow);

        // Inputs row: Elapsed (s), Azimuth (°), Temp (°C)
        LinearLayout inputsRow = new LinearLayout(activity);
        inputsRow.setOrientation(LinearLayout.HORIZONTAL);
        inputsRow.setPadding(0, Ui.dp(activity, 6), 0, 0);

        elapsedInput = decimalInput(R.string.rng_elapsed_hint, "3.00", false);
        azimuthInput = decimalInput(R.string.rng_azimuth_hint, "0.0", false);
        tempInput = decimalInput(R.string.rng_temp_hint, "20", true);

        inputsRow.addView(labeledField(R.string.rng_elapsed_hint, elapsedInput),
                new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        inputsRow.addView(labeledField(R.string.rng_azimuth_hint, azimuthInput),
                new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        inputsRow.addView(labeledField(R.string.rng_temp_hint, tempInput),
                new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.85f));
        flashSection.addView(inputsRow);

        soundInfoText = text(12, R.color.text_secondary);
        soundInfoText.setPadding(0, Ui.dp(activity, 2), 0, Ui.dp(activity, 6));
        flashSection.addView(soundInfoText);

        // Origin selector for Flash-to-Bang projection
        LinearLayout originHeader = new LinearLayout(activity);
        originHeader.setOrientation(LinearLayout.HORIZONTAL);
        originHeader.setGravity(Gravity.CENTER_VERTICAL);
        flashOriginLabel = text(13, R.color.text_secondary);
        originHeader.addView(flashOriginLabel, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        originHeader.addView(smallButton(R.string.rng_pick_my_gps, v -> {
            flashOriginUseGps = true;
            computeFlashToBang();
        }));
        originHeader.addView(smallButton(R.string.rng_pick_waypoint, v -> pickWaypoint((w) -> {
            flashOriginUseGps = false;
            flashOriginLat = w.latitude;
            flashOriginLon = w.longitude;
            flashOriginCustomName = w.name;
            computeFlashToBang();
        })));
        originHeader.addView(smallButton(R.string.rng_pick_coords, v -> promptCoordinates(R.string.rng_point_a, (lat, lon, label) -> {
            flashOriginUseGps = false;
            flashOriginLat = lat;
            flashOriginLon = lon;
            flashOriginCustomName = label;
            computeFlashToBang();
        })));
        flashSection.addView(originHeader);

        flashResultText = text(14, R.color.text_primary);
        flashResultText.setTypeface(Typeface.MONOSPACE);
        flashResultText.setTextIsSelectable(true);
        flashResultText.setPadding(0, Ui.dp(activity, 8), 0, Ui.dp(activity, 4));
        flashSection.addView(flashResultText);

        flashTargetActions = targetActionBar(
                () -> flashProjectedTarget,
                () -> activity.getString(R.string.rng_default_flash_wp,
                        TextCode.encode(flashProjectedTarget[0], flashProjectedTarget[1], TextCode.Tier.GLOBAL_STANDARD)));
        flashSection.addView(flashTargetActions);
        content.addView(flashSection);

        TextWatcher flashWatcher = new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override
            public void afterTextChanged(Editable s) {
                if (!suppressTextWatchers) computeFlashToBang();
            }
        };
        elapsedInput.addTextChangedListener(flashWatcher);
        azimuthInput.addTextChangedListener(flashWatcher);
        tempInput.addTextChangedListener(flashWatcher);

        // ==================== 2. TWO-POINTS SECTION ====================
        pointsSection = new LinearLayout(activity);
        pointsSection.setOrientation(LinearLayout.VERTICAL);
        pointsSection.setVisibility(View.GONE);

        pointsSection.addView(sectionHeader(R.string.rng_point_a));
        pointASummary = text(13, R.color.text_primary);
        pointASummary.setTypeface(Typeface.MONOSPACE);
        pointsSection.addView(pointASummary);
        LinearLayout pointABtns = new LinearLayout(activity);
        pointABtns.setOrientation(LinearLayout.HORIZONTAL);
        pointABtns.addView(smallButton(R.string.rng_pick_my_gps, v -> {
            pointAUseGps = true;
            computeTwoPoints();
        }));
        pointABtns.addView(smallButton(R.string.rng_pick_waypoint, v -> pickWaypoint(w -> {
            pointAUseGps = false;
            pointALat = w.latitude;
            pointALon = w.longitude;
            pointAAlt = w.altitude;
            pointAName = w.name;
            computeTwoPoints();
        })));
        pointABtns.addView(smallButton(R.string.rng_pick_coords, v -> promptCoordinates(R.string.rng_point_a, (lat, lon, label) -> {
            pointAUseGps = false;
            pointALat = lat;
            pointALon = lon;
            pointAAlt = Double.NaN;
            pointAName = label;
            computeTwoPoints();
        })));
        pointsSection.addView(pointABtns);

        LinearLayout swapRow = new LinearLayout(activity);
        swapRow.setOrientation(LinearLayout.HORIZONTAL);
        swapRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView pbHeader = sectionHeader(R.string.rng_point_b);
        swapRow.addView(pbHeader, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        swapRow.addView(borderless(R.string.rng_swap_points, v -> swapPointsAB()));
        pointsSection.addView(swapRow);

        pointBSummary = text(13, R.color.text_primary);
        pointBSummary.setTypeface(Typeface.MONOSPACE);
        pointsSection.addView(pointBSummary);
        LinearLayout pointBBtns = new LinearLayout(activity);
        pointBBtns.setOrientation(LinearLayout.HORIZONTAL);
        pointBBtns.addView(smallButton(R.string.rng_pick_my_gps, v -> {
            pointBUseGps = true;
            computeTwoPoints();
        }));
        pointBBtns.addView(smallButton(R.string.rng_pick_waypoint, v -> pickWaypoint(w -> {
            pointBUseGps = false;
            pointBLat = w.latitude;
            pointBLon = w.longitude;
            pointBAlt = w.altitude;
            pointBName = w.name;
            computeTwoPoints();
        })));
        pointBBtns.addView(smallButton(R.string.rng_pick_coords, v -> promptCoordinates(R.string.rng_point_b, (lat, lon, label) -> {
            pointBUseGps = false;
            pointBLat = lat;
            pointBLon = lon;
            pointBAlt = Double.NaN;
            pointBName = label;
            computeTwoPoints();
        })));
        pointsSection.addView(pointBBtns);

        twoPointsResult = text(14, R.color.text_primary);
        twoPointsResult.setTypeface(Typeface.MONOSPACE);
        twoPointsResult.setTextIsSelectable(true);
        twoPointsResult.setPadding(0, Ui.dp(activity, 10), 0, Ui.dp(activity, 4));
        pointsSection.addView(twoPointsResult);
        content.addView(pointsSection);

        // ==================== 3. TRIANGULATE SECTION ====================
        triSection = new LinearLayout(activity);
        triSection.setOrientation(LinearLayout.VERTICAL);
        triSection.setVisibility(View.GONE);

        LinearLayout triBtnRow = new LinearLayout(activity);
        triBtnRow.setOrientation(LinearLayout.HORIZONTAL);
        Button addLiveRayBtn = new Button(activity);
        addLiveRayBtn.setText(R.string.rng_tri_add_live);
        addLiveRayBtn.setOnClickListener(v -> addLiveCompassRay());
        triBtnRow.addView(addLiveRayBtn, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.2f));

        Button addManualRayBtn = new Button(activity);
        addManualRayBtn.setText(R.string.rng_tri_add_manual);
        addManualRayBtn.setOnClickListener(v -> promptManualRay());
        LinearLayout.LayoutParams manualLp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        manualLp.setMarginStart(Ui.dp(activity, 6));
        triBtnRow.addView(addManualRayBtn, manualLp);
        triSection.addView(triBtnRow);

        raysListLayout = new LinearLayout(activity);
        raysListLayout.setOrientation(LinearLayout.VERTICAL);
        raysListLayout.setPadding(0, Ui.dp(activity, 6), 0, Ui.dp(activity, 4));
        triSection.addView(raysListLayout);

        Button clearRaysBtn = borderless(R.string.rng_tri_clear, v -> {
            rays.clear();
            computeTriangulation();
        });
        triSection.addView(clearRaysBtn);

        triResultText = text(14, R.color.text_primary);
        triResultText.setTypeface(Typeface.MONOSPACE);
        triResultText.setTextIsSelectable(true);
        triResultText.setPadding(0, Ui.dp(activity, 4), 0, Ui.dp(activity, 4));
        triSection.addView(triResultText);

        triTargetActions = targetActionBar(
                () -> currentIntersection != null
                        ? new double[]{currentIntersection.latitude, currentIntersection.longitude}
                        : null,
                () -> activity.getString(R.string.rng_default_tri_wp,
                        TextCode.encode(currentIntersection.latitude, currentIntersection.longitude, TextCode.Tier.GLOBAL_STANDARD)));
        triSection.addView(triTargetActions);
        content.addView(triSection);

        // Shared local metric map
        map = new RangingMapView(activity);
        LinearLayout.LayoutParams mapLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        mapLp.topMargin = Ui.dp(activity, 10);
        content.addView(map, mapLp);

        TextView hint = text(12, R.color.text_secondary);
        hint.setText(R.string.rng_hint);
        hint.setPadding(0, Ui.dp(activity, 12), 0, 0);
        content.addView(hint);

        initDefaultPointB();
        setMode(MODE_FLASH);
    }

    private void initDefaultPointB() {
        List<Waypoint> all = store.getAll();
        if (!all.isEmpty()) {
            Waypoint w = all.get(0);
            pointBUseGps = false;
            pointBLat = w.latitude;
            pointBLon = w.longitude;
            pointBAlt = w.altitude;
            pointBName = w.name;
        }
    }

    @Override
    public View getView() {
        return root;
    }

    @Override
    public void onWaypointsChanged() {
        if (Double.isNaN(pointBLat) && !store.getAll().isEmpty()) {
            initDefaultPointB();
        }
        refreshActiveMode();
    }

    @Override
    public void onHidden() {
        visible = false;
        store.removeListener(this);
        orientation.stop();
        if (timerRunning) {
            handler.removeCallbacks(timerTick);
            timerRunning = false;
            flashTriggerBtn.setText(R.string.rng_flash_start);
            flashTriggerBtn.setTextColor(activity.getColor(R.color.state_good));
        }
    }

    @Override
    public void update(GnssMonitor m) {
        monitor = m;
        if (!visible) {
            visible = true;
            store.addListener(this);
            orientation.start();
        }
        Location loc = m.getLocation();
        if (loc != null) {
            orientation.setObserver(loc.getLatitude(), loc.getLongitude(),
                    loc.hasAltitude() ? loc.getAltitude() : 0, loc.getTime());
        }
        refreshActiveMode();
    }

    private void setMode(int newMode) {
        if (mode == MODE_FLASH && newMode != MODE_FLASH && timerRunning) toggleFlashTimer();
        this.mode = newMode;
        for (int i = 0; i < 3; i++) {
            modeChips[i].setSelected(i == newMode);
        }
        flashSection.setVisibility(newMode == MODE_FLASH ? View.VISIBLE : View.GONE);
        pointsSection.setVisibility(newMode == MODE_POINTS ? View.VISIBLE : View.GONE);
        triSection.setVisibility(newMode == MODE_TRIANGULATE ? View.VISIBLE : View.GONE);
        refreshActiveMode();
    }

    private void refreshActiveMode() {
        switch (mode) {
            case MODE_FLASH:
                computeFlashToBang();
                break;
            case MODE_POINTS:
                computeTwoPoints();
                break;
            case MODE_TRIANGULATE:
                computeTriangulation();
                break;
        }
    }

    private void onCompassUpdated() {
        if (!orientation.hasFrame()) {
            liveCompassText.setText(R.string.rng_live_heading_none);
            return;
        }
        float heading = orientation.getHeadingDegrees();
        String status = orientation.isCompassTrusted() ? "" : " ⚠";
        liveCompassText.setText(activity.getString(R.string.rng_live_heading, Format.bearing(heading) + status));
    }

    private void lockLiveCompassToAzimuth() {
        if (!orientation.hasFrame()) {
            Toast.makeText(activity, R.string.rng_live_heading_none, Toast.LENGTH_SHORT).show();
            return;
        }
        float heading = orientation.getHeadingDegrees();
        if (mode == MODE_TRIANGULATE) {
            addLiveCompassRay();
        } else {
            if (mode != MODE_FLASH) setMode(MODE_FLASH);
            azimuthInput.setText(String.format(Locale.ROOT, "%.1f", heading));
        }
    }

    // ---- 1. Flash-to-Bang implementation ------------------------------------------------------

    private void toggleFlashTimer() {
        if (!timerRunning) {
            // FLASH pressed: start stopwatch and auto-capture live compass azimuth if available
            timerRunning = true;
            flashStartRealtimeMs = SystemClock.elapsedRealtime();
            if (orientation.hasFrame()) {
                suppressTextWatchers = true;
                azimuthInput.setText(String.format(Locale.ROOT, "%.1f", orientation.getHeadingDegrees()));
                suppressTextWatchers = false;
            }
            flashTriggerBtn.setText(R.string.rng_flash_bang);
            flashTriggerBtn.setTextColor(activity.getColor(R.color.state_bad));
            handler.post(timerTick);
        } else {
            // BANG pressed: stop stopwatch and finalize calculation
            timerRunning = false;
            handler.removeCallbacks(timerTick);
            double sec = (SystemClock.elapsedRealtime() - flashStartRealtimeMs) / 1000.0;
            suppressTextWatchers = true;
            elapsedInput.setText(String.format(Locale.ROOT, "%.2f", sec));
            suppressTextWatchers = false;
            flashTriggerBtn.setText(R.string.rng_flash_start);
            flashTriggerBtn.setTextColor(activity.getColor(R.color.state_good));
            computeFlashToBang();
        }
    }

    private void resetFlashTimer() {
        timerRunning = false;
        handler.removeCallbacks(timerTick);
        flashTriggerBtn.setText(R.string.rng_flash_start);
        flashTriggerBtn.setTextColor(activity.getColor(R.color.state_good));
        suppressTextWatchers = true;
        elapsedInput.setText("0.00");
        suppressTextWatchers = false;
        computeFlashToBang();
    }

    private void computeFlashToBang() {
        double sec = parseDouble(elapsedInput.getText().toString(), 0);
        double az = parseDouble(azimuthInput.getText().toString(), Double.NaN);
        double tempC = parseDouble(tempInput.getText().toString(), 20.0);

        double speedMps = Geodesic.speedOfSound(tempC);
        double distM = Geodesic.acousticDistance(sec, tempC);
        soundInfoText.setText(activity.getString(R.string.rng_sound_info, speedMps, tempC));

        double origLat = Double.NaN;
        double origLon = Double.NaN;
        String origName;
        if (flashOriginUseGps) {
            Location loc = monitor != null ? monitor.getLocation() : null;
            if (loc != null) {
                origLat = loc.getLatitude();
                origLon = loc.getLongitude();
            }
            origName = activity.getString(R.string.rng_origin_me);
        } else {
            origLat = flashOriginLat;
            origLon = flashOriginLon;
            origName = flashOriginCustomName;
        }
        flashOriginLabel.setText(origName + (!Double.isNaN(origLat)
                ? " (" + Format.coordinates(origLat, origLon) + ")" : ""));

        StringBuilder sb = new StringBuilder();
        sb.append(activity.getString(R.string.rng_flash_result_dist, Format.distance(distM), Math.max(0, sec), speedMps));
        if (distM >= 1000) {
            sb.append(String.format(Locale.ROOT, "  (%.0f m)", distM));
        }

        if (!Double.isNaN(origLat) && !Double.isNaN(az) && distM > 0) {
            double normAz = ((az % 360) + 360) % 360;
            flashProjectedTarget = Geodesic.destination(origLat, origLon, normAz, distM);
            sb.append("\n\n").append(activity.getString(R.string.rng_flash_projected,
                    Format.distance(distM), Format.bearing(normAz))).append('\n');
            appendFormattedCoords(sb, flashProjectedTarget[0], flashProjectedTarget[1]);
            flashTargetActions.setVisibility(View.VISIBLE);
            map.setTwoPointData(origLat, origLon, "ORIGIN",
                    flashProjectedTarget[0], flashProjectedTarget[1], "BANG", true);
        } else {
            flashProjectedTarget = null;
            sb.append("\n\n").append(activity.getString(R.string.rng_flash_no_origin));
            flashTargetActions.setVisibility(View.GONE);
            map.setTwoPointData(origLat, origLon, "ORIGIN", Double.NaN, 0, null, true);
        }
        flashResultText.setText(sb);
    }

    // ---- 2. Two-Points Distance & Bearing implementation --------------------------------------

    private void swapPointsAB() {
        boolean tmpGps = pointAUseGps;
        double tmpLat = pointALat;
        double tmpLon = pointALon;
        double tmpAlt = pointAAlt;
        String tmpName = pointAName;

        pointAUseGps = pointBUseGps;
        pointALat = pointBLat;
        pointALon = pointBLon;
        pointAAlt = pointBAlt;
        pointAName = pointBName;

        pointBUseGps = tmpGps;
        pointBLat = tmpLat;
        pointBLon = tmpLon;
        pointBAlt = tmpAlt;
        pointBName = tmpName;

        computeTwoPoints();
    }

    private void computeTwoPoints() {
        Location me = monitor != null ? monitor.getLocation() : null;
        double latA = pointAUseGps ? (me != null ? me.getLatitude() : Double.NaN) : pointALat;
        double lonA = pointAUseGps ? (me != null ? me.getLongitude() : Double.NaN) : pointALon;
        double altA = pointAUseGps ? (me != null && me.hasAltitude() ? me.getAltitude() : Double.NaN) : pointAAlt;
        String labelA = pointAUseGps ? activity.getString(R.string.rng_origin_me) : pointAName;

        double latB = pointBUseGps ? (me != null ? me.getLatitude() : Double.NaN) : pointBLat;
        double lonB = pointBUseGps ? (me != null ? me.getLongitude() : Double.NaN) : pointBLon;
        double altB = pointBUseGps ? (me != null && me.hasAltitude() ? me.getAltitude() : Double.NaN) : pointBAlt;
        String labelB = pointBUseGps ? activity.getString(R.string.rng_origin_me) : pointBName;

        pointASummary.setText(formatPointSummary(labelA, latA, lonA));
        pointBSummary.setText(formatPointSummary(labelB, latB, lonB));

        if (!Double.isNaN(latA) && !Double.isNaN(latB)) {
            double dist = Waypoint.distance(latA, lonA, latB, lonB);
            double fwdBearing = Waypoint.bearing(latA, lonA, latB, lonB);
            double backBearing = Waypoint.bearing(latB, lonB, latA, lonA);
            String dAlt = (!Double.isNaN(altA) && !Double.isNaN(altB))
                    ? String.format(Locale.ROOT, "%+.1f m", altB - altA)
                    : Format.NONE;
            twoPointsResult.setText(activity.getString(R.string.rng_two_points_result,
                    Format.distance(dist), dist, Format.bearing(fwdBearing), Format.bearing(backBearing), dAlt));
            map.setTwoPointData(latA, lonA, "A", latB, lonB, "B", false);
        } else {
            twoPointsResult.setText(R.string.wp_no_fix);
            map.setTwoPointData(latA, lonA, "A", latB, lonB, "B", false);
        }
    }

    private String formatPointSummary(String name, double lat, double lon) {
        if (Double.isNaN(lat)) {
            return (name.isEmpty() ? "—" : name) + " (" + activity.getString(R.string.wp_no_fix) + ")";
        }
        String mgrs = Mgrs.format(lat, lon, 5);
        return (name.isEmpty() ? "" : name + ": ") + Format.coordinates(lat, lon)
                + (mgrs != null ? "\n" + mgrs : "");
    }

    // ---- 3. Multi-bearing Triangulation implementation ----------------------------------------

    private void addLiveCompassRay() {
        Location loc = monitor != null ? monitor.getLocation() : null;
        if (loc == null) {
            Toast.makeText(activity, R.string.toast_no_position, Toast.LENGTH_SHORT).show();
            return;
        }
        if (!orientation.hasFrame()) {
            Toast.makeText(activity, R.string.rng_live_heading_none, Toast.LENGTH_SHORT).show();
            return;
        }
        float az = orientation.getHeadingDegrees();
        String label = "S" + (rays.size() + 1);
        rays.add(new Geodesic.Ray(label, loc.getLatitude(), loc.getLongitude(), az));
        computeTriangulation();
    }

    private void promptManualRay() {
        Location me = monitor != null ? monitor.getLocation() : null;
        EditText posField = new EditText(activity);
        posField.setHint(R.string.wp_position_hint);
        posField.setSingleLine(true);
        posField.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        if (me != null) {
            posField.setText(Format.coordinates(me.getLatitude(), me.getLongitude()));
        }

        EditText azField = decimalInput(R.string.rng_azimuth_hint,
                orientation.hasFrame() ? String.format(Locale.ROOT, "%.1f", orientation.getHeadingDegrees()) : "0.0",
                false);

        LinearLayout pickRow = new LinearLayout(activity);
        pickRow.setOrientation(LinearLayout.HORIZONTAL);
        pickRow.addView(smallButton(R.string.rng_pick_my_gps, v -> {
            Location l = monitor != null ? monitor.getLocation() : null;
            if (l != null) posField.setText(Format.coordinates(l.getLatitude(), l.getLongitude()));
        }));
        pickRow.addView(smallButton(R.string.rng_pick_waypoint, v -> pickWaypoint(w ->
                posField.setText(Format.coordinates(w.latitude, w.longitude)))));

        LinearLayout box = new LinearLayout(activity);
        box.setOrientation(LinearLayout.VERTICAL);
        box.addView(posField);
        box.addView(pickRow);
        box.addView(labeledField(R.string.rng_azimuth_hint, azField));

        new AlertDialog.Builder(activity, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle(R.string.rng_tri_add_manual)
                .setView(WaypointsPage.padded(activity, box))
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(android.R.string.ok, (d, w) -> {
                    double refLat = me != null ? me.getLatitude() : Double.NaN;
                    double refLon = me != null ? me.getLongitude() : Double.NaN;
                    double[] p = CoordinateParser.parse(posField.getText().toString(), refLat, refLon);
                    double az = parseDouble(azField.getText().toString(), Double.NaN);
                    if (p == null || Double.isNaN(az)) {
                        Toast.makeText(activity, R.string.wp_invalid, Toast.LENGTH_LONG).show();
                        return;
                    }
                    String label = "S" + (rays.size() + 1);
                    rays.add(new Geodesic.Ray(label, p[0], p[1], az));
                    computeTriangulation();
                })
                .show();
    }

    private void computeTriangulation() {
        raysListLayout.removeAllViews();
        for (int i = 0; i < rays.size(); i++) {
            final int idx = i;
            Geodesic.Ray r = rays.get(i);
            LinearLayout row = new LinearLayout(activity);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            TextView tv = text(13, R.color.text_primary);
            tv.setTypeface(Typeface.MONOSPACE);
            tv.setText(String.format(Locale.ROOT, "%s: %s  →  %s",
                    r.label, Format.coordinates(r.latitude, r.longitude), Format.bearing(r.azimuthDeg)));
            row.addView(tv, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            Button del = borderless(R.string.wp_delete, v -> {
                rays.remove(idx);
                computeTriangulation();
            });
            row.addView(del);
            raysListLayout.addView(row);
        }

        Location me = monitor != null ? monitor.getLocation() : null;
        double meLat = me != null ? me.getLatitude() : Double.NaN;
        double meLon = me != null ? me.getLongitude() : 0;

        if (rays.size() < 2) {
            currentIntersection = null;
            triResultText.setText(R.string.rng_tri_need_rays);
            triResultText.setTextColor(activity.getColor(R.color.text_secondary));
            triTargetActions.setVisibility(View.GONE);
            map.setTriangulationData(rays, null, meLat, meLon);
            return;
        }

        currentIntersection = Geodesic.triangulate(rays);
        if (currentIntersection == null) {
            triResultText.setText(R.string.rng_tri_divergent);
            triResultText.setTextColor(activity.getColor(R.color.state_bad));
            triTargetActions.setVisibility(View.GONE);
            map.setTriangulationData(rays, null, meLat, meLon);
            return;
        }

        String spreadStr = rays.size() >= 3
                ? activity.getString(R.string.rng_tri_spread, currentIntersection.rmsMissM)
                : "";
        StringBuilder sb = new StringBuilder();
        sb.append(activity.getString(R.string.rng_tri_result,
                rays.size(), currentIntersection.maxAngleDeg, spreadStr)).append('\n');
        appendFormattedCoords(sb, currentIntersection.latitude, currentIntersection.longitude);

        if (!Double.isNaN(meLat)) {
            double distMe = Waypoint.distance(meLat, meLon, currentIntersection.latitude, currentIntersection.longitude);
            double bearMe = Waypoint.bearing(meLat, meLon, currentIntersection.latitude, currentIntersection.longitude);
            sb.append('\n').append(activity.getString(R.string.vc_from_me, Format.distance(distMe), Format.bearing(bearMe)));
        }
        boolean shallow = currentIntersection.maxAngleDeg < 15.0;
        if (shallow) {
            sb.append("\n\n").append(activity.getString(R.string.rng_tri_shallow_warn));
        }

        triResultText.setText(sb);
        triResultText.setTextColor(activity.getColor(shallow ? R.color.state_warn : R.color.text_primary));
        triTargetActions.setVisibility(View.VISIBLE);
        map.setTriangulationData(rays, currentIntersection, meLat, meLon);
    }

    // ---- Shared dialogs & helpers -------------------------------------------------------------

    private interface WaypointConsumer {
        void accept(Waypoint w);
    }

    private interface CoordsConsumer {
        void accept(double lat, double lon, String label);
    }

    private interface TargetSupplier {
        double[] get();
    }

    private interface NameSupplier {
        String get();
    }

    private void pickWaypoint(WaypointConsumer consumer) {
        List<Waypoint> all = store.getAll();
        if (all.isEmpty()) {
            Toast.makeText(activity, R.string.wp_none, Toast.LENGTH_SHORT).show();
            return;
        }
        String[] names = new String[all.size()];
        for (int i = 0; i < all.size(); i++) {
            Waypoint w = all.get(i);
            names[i] = w.name + " (" + Format.coordinates(w.latitude, w.longitude) + ")";
        }
        new AlertDialog.Builder(activity, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle(R.string.tool_waypoints)
                .setItems(names, (d, which) -> consumer.accept(all.get(which)))
                .show();
    }

    private void promptCoordinates(int titleRes, CoordsConsumer consumer) {
        EditText pos = new EditText(activity);
        pos.setHint(R.string.wp_position_hint);
        pos.setSingleLine(true);
        pos.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        TextView help = text(12, R.color.text_secondary);
        help.setText(R.string.wp_position_help);
        LinearLayout box = new LinearLayout(activity);
        box.setOrientation(LinearLayout.VERTICAL);
        box.addView(pos);
        box.addView(help);
        new AlertDialog.Builder(activity, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle(titleRes)
                .setView(WaypointsPage.padded(activity, box))
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(android.R.string.ok, (d, w) -> {
                    Location me = monitor != null ? monitor.getLocation() : null;
                    double[] p = CoordinateParser.parse(pos.getText().toString(),
                            me != null ? me.getLatitude() : Double.NaN,
                            me != null ? me.getLongitude() : Double.NaN);
                    if (p == null) {
                        Toast.makeText(activity, R.string.wp_invalid, Toast.LENGTH_LONG).show();
                        return;
                    }
                    consumer.accept(p[0], p[1], pos.getText().toString().trim());
                })
                .show();
    }

    private void appendFormattedCoords(StringBuilder sb, double lat, double lon) {
        sb.append(Format.coordinates(lat, lon)).append('\n');
        sb.append(Format.dms(lat, true)).append("  ").append(Format.dms(lon, false));
        String mgrs = Mgrs.format(lat, lon, 5);
        if (mgrs != null) sb.append('\n').append("MGRS: ").append(mgrs);
        String tc = TextCode.encode(lat, lon, TextCode.Tier.GLOBAL_STANDARD);
        if (tc != null) sb.append("  ·  ").append(tc);
    }

    private LinearLayout targetActionBar(TargetSupplier targetSupplier, NameSupplier defaultNameSupplier) {
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.addView(borderless(R.string.vc_copy, v -> {
            double[] t = targetSupplier.get();
            if (t == null) return;
            String s = Format.coordinates(t[0], t[1]);
            ClipboardManager cm = activity.getSystemService(ClipboardManager.class);
            cm.setPrimaryClip(ClipData.newPlainText("coordinates", s));
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                Toast.makeText(activity, activity.getString(R.string.toast_copied_value, s), Toast.LENGTH_SHORT).show();
            }
        }));
        row.addView(borderless(R.string.rng_save_waypoint, v -> {
            double[] t = targetSupplier.get();
            if (t == null) return;
            Waypoint w = store.add(defaultNameSupplier.get(), t[0], t[1], Double.NaN);
            Toast.makeText(activity, activity.getString(R.string.avg_saved) + ": " + w.name, Toast.LENGTH_SHORT).show();
        }));
        row.addView(borderless(R.string.wp_navigate, v -> {
            double[] t = targetSupplier.get();
            if (t == null) return;
            Waypoint w = store.add(defaultNameSupplier.get(), t[0], t[1], Double.NaN);
            store.setTarget(w);
            Toast.makeText(activity, activity.getString(R.string.wp_target, w.name), Toast.LENGTH_SHORT).show();
        }));
        row.addView(borderless(R.string.wp_open_map, v -> {
            double[] t = targetSupplier.get();
            if (t == null) return;
            String label = Uri.encode(defaultNameSupplier.get());
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(String.format(Locale.ROOT,
                    "geo:%1$.7f,%2$.7f?q=%1$.7f,%2$.7f(%3$s)", t[0], t[1], label)));
            try {
                activity.startActivity(intent);
            } catch (ActivityNotFoundException e) {
                Toast.makeText(activity, R.string.toast_no_app, Toast.LENGTH_SHORT).show();
            }
        }));
        row.setVisibility(View.GONE);
        return row;
    }

    private EditText decimalInput(int hintRes, String initial, boolean signed) {
        EditText e = new EditText(activity);
        e.setHint(hintRes);
        e.setSingleLine(true);
        int flags = InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL;
        if (signed) flags |= InputType.TYPE_NUMBER_FLAG_SIGNED;
        e.setInputType(flags);
        e.setTextColor(activity.getColor(R.color.text_primary));
        e.setText(initial);
        return e;
    }

    private LinearLayout labeledField(int labelRes, EditText field) {
        LinearLayout box = new LinearLayout(activity);
        box.setOrientation(LinearLayout.VERTICAL);
        TextView lbl = text(11, R.color.text_secondary);
        lbl.setText(labelRes);
        box.addView(lbl);
        box.addView(field);
        return box;
    }

    private TextView sectionHeader(int res) {
        TextView t = text(12, R.color.accent);
        t.setText(res);
        t.setAllCaps(true);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setLetterSpacing(0.08f);
        t.setPadding(0, Ui.dp(activity, 10), 0, Ui.dp(activity, 2));
        return t;
    }

    private Button borderless(int textRes, View.OnClickListener l) {
        Button b = new Button(activity, null, android.R.attr.borderlessButtonStyle);
        b.setText(textRes);
        b.setTextColor(activity.getColor(R.color.accent));
        b.setOnClickListener(l);
        return b;
    }

    private Button smallButton(int textRes, View.OnClickListener l) {
        Button b = new Button(activity, null, android.R.attr.borderlessButtonStyle);
        b.setText(textRes);
        b.setTextSize(12);
        b.setTextColor(activity.getColor(R.color.accent));
        b.setOnClickListener(l);
        return b;
    }

    private TextView text(float sp, int colorRes) {
        TextView t = new TextView(activity);
        t.setTextSize(sp);
        t.setTextColor(activity.getColor(colorRes));
        return t;
    }

    private static double parseDouble(String s, double fallback) {
        if (s == null) return fallback;
        String clean = s.trim().replace(',', '.');
        if (clean.isEmpty()) return fallback;
        try {
            double value = Double.parseDouble(clean);
            return Double.isFinite(value) ? value : fallback;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}

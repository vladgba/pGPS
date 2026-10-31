package x.vladgba.gps.ui;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.location.Location;
import android.os.Build;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import x.vladgba.gps.R;
import x.vladgba.gps.gnss.GnssMonitor;
import x.vladgba.gps.nav.BacktrackNavigator;
import x.vladgba.gps.nav.BacktrackService;
import x.vladgba.gps.nav.Trail;

/** Record a breadcrumb trail, then follow it back to where it started. */
final class BacktrackPage implements Page, BacktrackService.Listener, Trail.Listener {

    private static final int REQUEST_NOTIFICATIONS = 3;

    private final Activity activity;
    private final Trail trail;
    private final ScrollView root;
    private final TextView state;
    private final TextView details;
    private final Button primary;
    private final Button secondary;
    private final Button clear;
    private final TrailMapView map;
    private final NavigationArrowView arrow;
    private GnssMonitor monitor;

    BacktrackPage(Activity activity) {
        this.activity = activity;
        trail = Trail.get(activity);
        root = new ScrollView(activity);
        LinearLayout list = new LinearLayout(activity);
        list.setOrientation(LinearLayout.VERTICAL);
        int pad = Ui.dp(activity, 16);
        list.setPadding(pad, pad, pad, pad);
        root.addView(list);

        state = new TextView(activity);
        state.setTextSize(20);
        state.setTypeface(Typeface.DEFAULT_BOLD);
        state.setTextColor(activity.getColor(R.color.text_primary));
        list.addView(state);
        details = new TextView(activity);
        details.setTextSize(14);
        details.setTypeface(Typeface.MONOSPACE);
        details.setTextColor(activity.getColor(R.color.text_primary));
        details.setPadding(0, Ui.dp(activity, 4), 0, Ui.dp(activity, 8));
        list.addView(details);

        LinearLayout buttons = new LinearLayout(activity);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        primary = new Button(activity);
        secondary = new Button(activity);
        buttons.addView(primary, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        buttons.addView(secondary, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        list.addView(buttons);

        arrow = new NavigationArrowView(activity);
        LinearLayout.LayoutParams arrowLp = new LinearLayout.LayoutParams(Ui.dp(activity, 200), Ui.dp(activity, 200));
        arrowLp.gravity = Gravity.CENTER_HORIZONTAL;
        list.addView(arrow, arrowLp);

        map = new TrailMapView(activity);
        LinearLayout.LayoutParams mapLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        mapLp.topMargin = Ui.dp(activity, 8);
        list.addView(map, mapLp);

        clear = new Button(activity, null, android.R.attr.borderlessButtonStyle);
        clear.setText(R.string.bt_clear);
        clear.setTextColor(activity.getColor(R.color.accent));
        clear.setOnClickListener(v -> confirmClear());
        list.addView(clear);

        TextView hint = new TextView(activity);
        hint.setText(R.string.bt_hint);
        hint.setTextSize(12);
        hint.setTextColor(activity.getColor(R.color.text_secondary));
        list.addView(hint);

        BacktrackService.addListener(this);
        trail.addListener(this);
    }

    @Override
    public View getView() {
        return root;
    }

    @Override
    public void onBacktrackChanged() {
        render();
    }

    @Override
    public void onTrailChanged() {
        render();
    }

    @Override
    public void onHidden() {
        arrow.setActive(false);
    }

    @Override
    public void update(GnssMonitor m) {
        monitor = m;
        render();
    }

    private void render() {
        BacktrackService.Mode mode = BacktrackService.getMode();
        BacktrackNavigator.Guidance g = BacktrackService.getGuidance();
        Location me = monitor != null ? monitor.getLocation() : null;
        int count = trail.getPoints().size();

        switch (mode) {
            case RECORDING:
                state.setText(R.string.bt_state_recording);
                state.setTextColor(activity.getColor(R.color.state_bad));
                details.setText(activity.getString(R.string.bt_trail_stats, count, Format.distance(trail.getLengthM())));
                setButtons(R.string.bt_return, v -> startReturn(), R.string.bt_pause, v -> BacktrackService.stop(activity));
                break;
            case RETURNING:
                state.setText(g != null && g.arrived ? R.string.bt_arrived : R.string.bt_state_returning);
                state.setTextColor(activity.getColor(g != null && g.arrived ? R.color.state_good : R.color.accent));
                if (g == null) {
                    details.setText(R.string.wp_no_fix);
                } else {
                    details.setText(activity.getString(R.string.bt_guidance,
                            Format.distance(g.remaining), Format.distance(g.distanceToTarget),
                            Format.bearing(g.bearingToTarget), Format.distance(g.offTrail),
                            Math.round(g.progress * 100)));
                }
                setButtons(R.string.bt_stop, v -> BacktrackService.stop(activity), 0, null);
                break;
            default:
                state.setText(count == 0 ? R.string.bt_state_idle : R.string.bt_state_ready);
                state.setTextColor(activity.getColor(R.color.text_primary));
                details.setText(count == 0 ? activity.getString(R.string.bt_empty)
                        : activity.getString(R.string.bt_trail_stats, count, Format.distance(trail.getLengthM())));
                if (count == 0) {
                    setButtons(R.string.bt_start, v -> startRecording(), 0, null);
                } else {
                    setButtons(R.string.bt_return, v -> startReturn(), R.string.bt_continue, v -> startRecording());
                }
                break;
        }
        clear.setVisibility(count > 0 && mode != BacktrackService.Mode.RECORDING ? View.VISIBLE : View.GONE);

        boolean returning = mode == BacktrackService.Mode.RETURNING && g != null;
        arrow.setVisibility(returning ? View.VISIBLE : View.GONE);
        arrow.setActive(returning);
        if (returning && me != null) {
            arrow.setNavigation(g.bearingToTarget, me.hasBearing() ? me.getBearing() : Float.NaN,
                    me.hasSpeed() ? me.getSpeed() : 0, me.getLatitude(), me.getLongitude(), me.getAltitude(), me.getTime());
        }
        map.setData(trail.getPoints(), me != null ? me.getLatitude() : Double.NaN, me != null ? me.getLongitude() : 0,
                returning ? g.targetLat : Double.NaN, returning ? g.targetLon : 0);
    }

    private void setButtons(int primaryText, View.OnClickListener primaryAction, int secondaryText,
                            View.OnClickListener secondaryAction) {
        primary.setText(primaryText);
        primary.setOnClickListener(primaryAction);
        secondary.setVisibility(secondaryText != 0 ? View.VISIBLE : View.GONE);
        if (secondaryText != 0) {
            secondary.setText(secondaryText);
            secondary.setOnClickListener(secondaryAction);
        }
    }

    private boolean ready() {
        if (monitor == null || !monitor.hasPermission()) {
            Toast.makeText(activity, R.string.toast_need_permission, Toast.LENGTH_SHORT).show();
            return false;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && activity.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            activity.requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQUEST_NOTIFICATIONS);
        }
        return true;
    }

    private void startRecording() {
        if (ready()) BacktrackService.startRecording(activity);
    }

    private void startReturn() {
        if (trail.getPoints().size() < 2) {
            Toast.makeText(activity, R.string.bt_too_short, Toast.LENGTH_LONG).show();
            return;
        }
        if (ready()) BacktrackService.startReturn(activity);
    }

    private void confirmClear() {
        new AlertDialog.Builder(activity, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle(R.string.bt_clear_title)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.bt_clear, (d, w) -> trail.clear())
                .show();
    }
}

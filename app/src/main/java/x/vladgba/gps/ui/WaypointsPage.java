package x.vladgba.gps.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.graphics.Typeface;
import android.location.Location;
import android.net.Uri;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import x.vladgba.gps.R;
import x.vladgba.gps.coords.CoordinateParser;
import x.vladgba.gps.coords.Mgrs;
import x.vladgba.gps.gnss.GnssMonitor;
import x.vladgba.gps.nav.Waypoint;
import x.vladgba.gps.nav.WaypointStore;

import java.util.Locale;

/** Saved points and navigation to one of them. */
final class WaypointsPage implements Page, WaypointStore.Listener {

    private final Activity activity;
    private final WaypointStore store;
    private final ScrollView root;
    private final LinearLayout navCard;
    private final TextView navTitle;
    private final TextView navInfo;
    private final NavigationArrowView arrow;
    private final LinearLayout list;
    private GnssMonitor monitor;
    private long listRenderedAtMs;

    /** Opens the voice code tool for a waypoint. */
    interface VoiceCodeSharer {
        void share(Waypoint waypoint);
    }

    private final VoiceCodeSharer voiceCodeSharer;

    WaypointsPage(Activity activity, VoiceCodeSharer voiceCodeSharer) {
        this.activity = activity;
        this.voiceCodeSharer = voiceCodeSharer;
        store = WaypointStore.get(activity);
        root = new ScrollView(activity);
        LinearLayout content = new LinearLayout(activity);
        content.setOrientation(LinearLayout.VERTICAL);
        int pad = Ui.dp(activity, 16);
        content.setPadding(pad, pad, pad, pad);
        root.addView(content);

        navCard = new LinearLayout(activity);
        navCard.setOrientation(LinearLayout.VERTICAL);
        navCard.setGravity(Gravity.CENTER_HORIZONTAL);
        navTitle = new TextView(activity);
        navTitle.setTextSize(16);
        navTitle.setTypeface(Typeface.DEFAULT_BOLD);
        navTitle.setTextColor(activity.getColor(R.color.text_primary));
        navCard.addView(navTitle);
        arrow = new NavigationArrowView(activity);
        navCard.addView(arrow, new LinearLayout.LayoutParams(Ui.dp(activity, 240), Ui.dp(activity, 240)));
        navInfo = new TextView(activity);
        navInfo.setTypeface(Typeface.MONOSPACE);
        navInfo.setTextSize(13);
        navInfo.setGravity(Gravity.CENTER_HORIZONTAL);
        navInfo.setTextColor(activity.getColor(R.color.text_primary));
        navCard.addView(navInfo);
        Button stop = new Button(activity, null, android.R.attr.borderlessButtonStyle);
        stop.setText(R.string.wp_stop);
        stop.setTextColor(activity.getColor(R.color.accent));
        stop.setOnClickListener(v -> store.setTarget(null));
        navCard.addView(stop);
        content.addView(navCard);

        LinearLayout buttons = new LinearLayout(activity);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        Button addCurrent = new Button(activity);
        addCurrent.setText(R.string.wp_add_current);
        addCurrent.setOnClickListener(v -> addCurrent());
        buttons.addView(addCurrent, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        Button addManual = new Button(activity);
        addManual.setText(R.string.wp_add_manual);
        addManual.setOnClickListener(v -> addManual());
        buttons.addView(addManual, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        content.addView(buttons);

        list = new LinearLayout(activity);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(0, Ui.dp(activity, 12), 0, 0);
        content.addView(list);

        store.addListener(this);
        renderList();
    }

    @Override
    public View getView() {
        return root;
    }

    @Override
    public void onWaypointsChanged() {
        renderList();
        if (monitor != null) update(monitor);
    }

    @Override
    public void onHidden() {
        arrow.setActive(false);
    }

    @Override
    public void update(GnssMonitor m) {
        monitor = m;
        Waypoint target = store.getTarget();
        navCard.setVisibility(target != null ? View.VISIBLE : View.GONE);
        arrow.setActive(target != null);
        if (target != null) renderNavigation(target, m.hasFreshFix() ? m.getLocation() : null);
        // Distances in the list follow the position, but not every second.
        if (System.currentTimeMillis() - listRenderedAtMs > 5_000) renderList();
    }

    private void renderNavigation(Waypoint target, Location loc) {
        navTitle.setText(activity.getString(R.string.wp_target, target.name));
        if (loc == null) {
            arrow.setNavigation(Double.NaN, Float.NaN, 0, Double.NaN, 0, 0, 0);
            navInfo.setText(R.string.wp_no_fix);
            return;
        }
        double distance = Waypoint.distance(loc.getLatitude(), loc.getLongitude(), target.latitude, target.longitude);
        double bearing = Waypoint.bearing(loc.getLatitude(), loc.getLongitude(), target.latitude, target.longitude);
        arrow.setNavigation(bearing, loc.hasBearing() ? loc.getBearing() : Float.NaN,
                loc.hasSpeed() ? loc.getSpeed() : 0, loc.getLatitude(), loc.getLongitude(), loc.getAltitude(), loc.getTime());

        StringBuilder sb = new StringBuilder();
        sb.append(Format.distance(distance)).append("  ·  ").append(Format.bearing(bearing)).append('\n');
        float heading = arrow.getHeading();
        if (!Float.isNaN(heading)) {
            double relative = ((bearing - heading) % 360 + 540) % 360 - 180;
            sb.append(String.format(Locale.ROOT, "turn %s %.0f°", relative >= 0 ? "right" : "left", Math.abs(relative)));
        }
        if (loc.hasSpeed() && loc.getSpeed() >= 0.5f) {
            sb.append(String.format(Locale.ROOT, "  ·  ETA %s", Format.duration((long) (distance / loc.getSpeed() * 1000))));
        }
        if (loc.hasAccuracy()) sb.append(String.format(Locale.ROOT, "%n± %.0f m", loc.getAccuracy()));
        switch (arrow.getHeadingSource()) {
            case COMPASS: sb.append('\n').append(activity.getString(R.string.wp_heading_compass)); break;
            case COURSE: sb.append('\n').append(activity.getString(R.string.wp_heading_course)); break;
            case DISTURBED: sb.append('\n').append(activity.getString(R.string.wp_heading_disturbed)); break;
            default: break;
        }
        navInfo.setText(sb);
    }

    private void renderList() {
        listRenderedAtMs = System.currentTimeMillis();
        list.removeAllViews();
        if (store.getAll().isEmpty()) {
            TextView none = new TextView(activity);
            none.setText(R.string.wp_none);
            none.setTextColor(activity.getColor(R.color.text_secondary));
            list.addView(none);
            return;
        }
        Location loc = monitor != null ? monitor.getLocation() : null;
        Waypoint target = store.getTarget();
        for (Waypoint w : store.getAll()) {
            LinearLayout row = new LinearLayout(activity);
            row.setOrientation(LinearLayout.VERTICAL);
            row.setPadding(0, Ui.dp(activity, 8), 0, Ui.dp(activity, 8));
            row.setBackgroundResource(android.R.drawable.list_selector_background);
            row.setClickable(true);
            row.setOnClickListener(v -> showMenu(v, w));
            TextView name = new TextView(activity);
            name.setText(target != null && target.id == w.id ? "➤ " + w.name : w.name);
            name.setTextSize(16);
            name.setTextColor(activity.getColor(target != null && target.id == w.id ? R.color.accent : R.color.text_primary));
            row.addView(name);
            TextView info = new TextView(activity);
            String text = Format.coordinates(w.latitude, w.longitude);
            String grid = Mgrs.format(w.latitude, w.longitude, 5);
            if (grid != null) text += "\n" + grid;
            if (loc != null) {
                text += "  ·  " + Format.distance(Waypoint.distance(loc.getLatitude(), loc.getLongitude(), w.latitude, w.longitude))
                        + " " + Format.bearing(Waypoint.bearing(loc.getLatitude(), loc.getLongitude(), w.latitude, w.longitude));
            }
            info.setText(text);
            info.setTextSize(12);
            info.setTextColor(activity.getColor(R.color.text_secondary));
            row.addView(info);
            list.addView(row);
        }
    }

    private void showMenu(View anchor, Waypoint w) {
        PopupMenu menu = new PopupMenu(activity, anchor);
        menu.getMenu().add(0, 1, 0, R.string.wp_navigate);
        menu.getMenu().add(0, 2, 1, R.string.wp_open_map);
        menu.getMenu().add(0, 3, 2, R.string.wp_share);
        menu.getMenu().add(0, 5, 3, R.string.wp_share_voice);
        menu.getMenu().add(0, 4, 4, R.string.wp_delete);
        menu.setOnMenuItemClickListener(item -> {
            switch (item.getItemId()) {
                case 1:
                    store.setTarget(w);
                    root.smoothScrollTo(0, 0);
                    return true;
                case 2:
                    start(new Intent(Intent.ACTION_VIEW, Uri.parse(String.format(Locale.ROOT,
                            "geo:%1$.7f,%2$.7f?q=%1$.7f,%2$.7f(%3$s)", w.latitude, w.longitude, Uri.encode(w.name)))));
                    return true;
                case 3:
                    Intent send = new Intent(Intent.ACTION_SEND).setType("text/plain")
                            .putExtra(Intent.EXTRA_TEXT, w.name + "\n" + Format.coordinates(w.latitude, w.longitude)
                                    + String.format(Locale.ROOT, "\nhttps://www.openstreetmap.org/?mlat=%1$.7f&mlon=%2$.7f#map=17/%1$.7f/%2$.7f",
                                    w.latitude, w.longitude));
                    start(Intent.createChooser(send, activity.getString(R.string.wp_share)));
                    return true;
                case 4:
                    store.delete(w.id);
                    return true;
                case 5:
                    voiceCodeSharer.share(w);
                    return true;
                default:
                    return false;
            }
        });
        menu.show();
    }

    private void start(Intent intent) {
        try {
            activity.startActivity(intent);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(activity, R.string.toast_no_app, Toast.LENGTH_SHORT).show();
        }
    }

    private void addCurrent() {
        Location loc = monitor != null ? monitor.getLocation() : null;
        if (loc == null) {
            Toast.makeText(activity, R.string.toast_no_position, Toast.LENGTH_SHORT).show();
            return;
        }
        EditText name = nameField();
        new AlertDialog.Builder(activity, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle(R.string.wp_add_current)
                .setView(padded(activity, name))
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(android.R.string.ok, (d, w) -> store.add(nameOf(name), loc.getLatitude(),
                        loc.getLongitude(), loc.hasAltitude() ? loc.getAltitude() : Double.NaN))
                .show();
    }

    private void addManual() {
        EditText name = nameField();
        EditText position = new EditText(activity);
        position.setHint(R.string.wp_position_hint);
        position.setSingleLine(true);
        position.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        TextView help = new TextView(activity);
        help.setText(R.string.wp_position_help);
        help.setTextSize(12);
        help.setTextColor(activity.getColor(R.color.text_secondary));
        LinearLayout box = new LinearLayout(activity);
        box.setOrientation(LinearLayout.VERTICAL);
        box.addView(name);
        box.addView(position);
        box.addView(help);
        new AlertDialog.Builder(activity, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle(R.string.wp_add_manual)
                .setView(padded(activity, box))
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(android.R.string.ok, (d, w) -> {
                    Location me = monitor != null ? monitor.getLocation() : null;
                    double[] p = CoordinateParser.parse(position.getText().toString(),
                            me != null ? me.getLatitude() : Double.NaN, me != null ? me.getLongitude() : Double.NaN);
                    if (p == null) {
                        Toast.makeText(activity, R.string.wp_invalid, Toast.LENGTH_LONG).show();
                        return;
                    }
                    store.add(nameOf(name), p[0], p[1], Double.NaN);
                })
                .show();
    }

    private EditText nameField() {
        EditText name = new EditText(activity);
        name.setHint(R.string.wp_name);
        name.setSingleLine(true);
        name.setText(activity.getString(R.string.wp_default_name, store.getAll().size() + 1));
        name.selectAll();
        return name;
    }

    private String nameOf(EditText e) {
        String n = e.getText().toString().trim();
        return n.isEmpty() ? activity.getString(R.string.wp_default_name, store.getAll().size() + 1) : n;
    }

    /** Wraps a dialog content view with the usual side padding. */
    static View padded(Context context, View child) {
        LinearLayout box = new LinearLayout(context);
        int pad = Ui.dp(context, 20);
        box.setPadding(pad, Ui.dp(context, 8), pad, 0);
        box.addView(child, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        return box;
    }
}

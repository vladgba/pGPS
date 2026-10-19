package com.vladgba.gpstools.ui;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.widget.Toast;

import com.vladgba.gpstools.coords.BritishGrid;
import com.vladgba.gpstools.coords.Mgrs;
import com.vladgba.gpstools.coords.PlusCode;
import com.vladgba.gpstools.coords.Utm;
import com.vladgba.gpstools.coords.TextCode;
import com.vladgba.gpstools.coords.VoiceCode;
import android.graphics.Typeface;
import android.location.Location;
import android.os.Build;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.vladgba.gpstools.R;
import com.vladgba.gpstools.gnss.ConstellationCount;
import com.vladgba.gpstools.gnss.Constellations;
import com.vladgba.gpstools.gnss.GnssMonitor;
import com.vladgba.gpstools.gnss.NmeaFix;

import java.util.Locale;

/** Position, accuracy, DOP, satellite totals and timing in a label/value list. */
public final class StatusPage implements Page {

    private final Context context;
    private final ScrollView root;
    private final LinearLayout list;

    private final TextView state;
    private final TextView latitude;
    private final TextView longitude;
    private final TextView latitudeDms;
    private final TextView longitudeDms;
    private final TextView ddm;
    private final TextView utm;
    private final TextView mgrs;
    private final TextView bng;
    private final TextView plusCode;
    private final TextView voiceCode;
    private final TextView textCodeStandard;
    private final TextView textCodePrecise;
    private final TextView altitudeEllipsoid;
    private final TextView altitudeMsl;
    private final TextView geoid;
    private final TextView horizontalAccuracy;
    private final TextView verticalAccuracy;
    private final TextView speed;
    private final TextView speedAccuracy;
    private final TextView bearing;
    private final TextView bearingAccuracy;
    private final TextView pdop;
    private final TextView hdop;
    private final TextView vdop;
    private final TextView nmeaQuality;
    private final TextView used;
    private final TextView inView;
    private final TextView signals;
    private final LinearLayout constellationRows;
    private final TextView ttff;
    private final TextView fixTime;
    private final TextView fixAge;
    private final TextView session;

    public StatusPage(Context context) {
        this.context = context;
        root = new ScrollView(context);
        list = new LinearLayout(context);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(0, 0, 0, Ui.dp(context, 16));
        root.addView(list);

        section(R.string.section_position);
        state = row(R.string.label_state);
        latitude = row(R.string.label_latitude);
        longitude = row(R.string.label_longitude);
        latitudeDms = row(R.string.label_lat_dms);
        longitudeDms = row(R.string.label_lon_dms);
        section(R.string.section_formats);
        ddm = copyRow(R.string.label_ddm);
        utm = copyRow(R.string.label_utm);
        mgrs = copyRow(R.string.label_mgrs);
        bng = copyRow(R.string.label_bng);
        plusCode = copyRow(R.string.label_plus_code);
        textCodeStandard = copyRow(R.string.label_text_code_standard);
        textCodePrecise = copyRow(R.string.label_text_code_precise);
        voiceCode = copyRow(R.string.label_voice_code);

        section(R.string.section_altitude);
        altitudeEllipsoid = row(R.string.label_alt_ellipsoid);
        altitudeMsl = row(R.string.label_alt_msl);
        geoid = row(R.string.label_geoid);

        section(R.string.section_accuracy);
        horizontalAccuracy = row(R.string.label_h_acc);
        verticalAccuracy = row(R.string.label_v_acc);
        speed = row(R.string.label_speed);
        speedAccuracy = row(R.string.label_speed_acc);
        bearing = row(R.string.label_bearing);
        bearingAccuracy = row(R.string.label_bearing_acc);

        section(R.string.section_quality);
        pdop = row(R.string.label_pdop);
        hdop = row(R.string.label_hdop);
        vdop = row(R.string.label_vdop);
        nmeaQuality = row(R.string.label_nmea_quality);

        section(R.string.section_satellites);
        used = row(R.string.label_used);
        inView = row(R.string.label_in_view);
        signals = row(R.string.label_signals);

        section(R.string.section_constellations);
        constellationRows = new LinearLayout(context);
        constellationRows.setOrientation(LinearLayout.VERTICAL);
        list.addView(constellationRows);

        section(R.string.section_time);
        ttff = row(R.string.label_ttff);
        fixTime = row(R.string.label_fix_time);
        fixAge = row(R.string.label_fix_age);
        session = row(R.string.label_session);

        // Coordinates are what people copy; let them select the text.
        latitude.setTextIsSelectable(true);
        longitude.setTextIsSelectable(true);
        latitudeDms.setTextIsSelectable(true);
        longitudeDms.setTextIsSelectable(true);
    }

    @Override
    public View getView() {
        return root;
    }

    @Override
    public void update(GnssMonitor m) {
        Location loc = m.getLocation();
        boolean fresh = m.hasFreshFix();
        NmeaFix nmea = m.getNmeaFix();
        boolean nmeaFresh = m.isNmeaFresh();

        state.setText(stateText(m, fresh, nmeaFresh ? nmea.getFixType() : NmeaFix.FIX_TYPE_UNKNOWN));
        state.setTextColor(context.getColor(fresh ? R.color.state_good : R.color.text_primary));

        if (loc != null) {
            latitude.setText(Format.degrees(loc.getLatitude()));
            longitude.setText(Format.degrees(loc.getLongitude()));
            latitudeDms.setText(Format.dms(loc.getLatitude(), true));
            longitudeDms.setText(Format.dms(loc.getLongitude(), false));
            renderFormats(loc.getLatitude(), loc.getLongitude());
            altitudeEllipsoid.setText(loc.hasAltitude() ? Format.meters(loc.getAltitude()) : Format.NONE);
            horizontalAccuracy.setText(loc.hasAccuracy() ? Format.accuracy(loc.getAccuracy()) : Format.NONE);
            speed.setText(loc.hasSpeed() ? Format.speed(loc.getSpeed()) : Format.NONE);
            bearing.setText(loc.hasBearing() ? Format.bearing(loc.getBearing()) : Format.NONE);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                verticalAccuracy.setText(loc.hasVerticalAccuracy()
                        ? Format.accuracy(loc.getVerticalAccuracyMeters()) : Format.NONE);
                speedAccuracy.setText(loc.hasSpeedAccuracy()
                        ? Format.speedAccuracy(loc.getSpeedAccuracyMetersPerSecond()) : Format.NONE);
                bearingAccuracy.setText(loc.hasBearingAccuracy()
                        ? Format.angleAccuracy(loc.getBearingAccuracyDegrees()) : Format.NONE);
            } else {
                verticalAccuracy.setText(Format.NONE);
                speedAccuracy.setText(Format.NONE);
                bearingAccuracy.setText(Format.NONE);
            }
            fixTime.setText(Format.utc(loc.getTime()));
            fixAge.setText(Format.seconds(m.getFixAgeMs()));
        } else {
            for (TextView v : new TextView[]{latitude, longitude, latitudeDms, longitudeDms, ddm, utm, mgrs, bng, plusCode, voiceCode, textCodeStandard, textCodePrecise,
                    altitudeEllipsoid, horizontalAccuracy, verticalAccuracy, speed, speedAccuracy,
                    bearing, bearingAccuracy, fixTime, fixAge}) {
                v.setText(Format.NONE);
            }
        }
        // A stale fix stays visible but dimmed, so the last known position is not lost.
        float alpha = fresh ? 1f : 0.5f;
        latitude.setAlpha(alpha);
        longitude.setAlpha(alpha);
        latitudeDms.setAlpha(alpha);
        longitudeDms.setAlpha(alpha);
        for (TextView v : new TextView[]{ddm, utm, mgrs, bng, plusCode, voiceCode, textCodeStandard, textCodePrecise}) v.setAlpha(alpha);

        altitudeMsl.setText(Format.meters(mslAltitude(loc, nmea, nmeaFresh)));
        geoid.setText(nmeaFresh ? Format.meters(nmea.getGeoidSeparation()) : Format.NONE);

        pdop.setText(nmeaFresh ? Format.dop(nmea.getPdop()) : Format.NONE);
        hdop.setText(nmeaFresh ? Format.dop(nmea.getHdop()) : Format.NONE);
        vdop.setText(nmeaFresh ? Format.dop(nmea.getVdop()) : Format.NONE);
        String quality = nmeaFresh ? NmeaFix.qualityName(nmea.getGgaQuality()) : null;
        nmeaQuality.setText(quality != null ? quality : Format.NONE);

        used.setText(String.valueOf(m.getSatellitesUsed()));
        inView.setText(String.valueOf(m.getSatellitesInView()));
        signals.setText(String.valueOf(m.getSatellites().size()));
        renderConstellations(m);

        int ttffMs = m.getTtffMs();
        ttff.setText(ttffMs >= 0 ? Format.seconds(ttffMs) : Format.NONE);
        session.setText(m.isRunning() ? Format.duration(m.getSessionDurationMs()) : Format.NONE);
    }

    private void renderFormats(double lat, double lon) {
        ddm.setText(context.getString(R.string.format_pair, Format.ddm(lat, true), Format.ddm(lon, false)));
        Utm u = Utm.from(lat, lon);
        utm.setText(u != null ? u.format() : context.getString(R.string.format_polar));
        String m = Mgrs.format(lat, lon, 5);
        mgrs.setText(m != null ? m : context.getString(R.string.format_polar));
        BritishGrid g = BritishGrid.fromWgs84(lat, lon);
        bng.setText(g != null ? context.getString(R.string.format_two_lines, g.gridReference(), g.numeric())
                : context.getString(R.string.format_outside_gb));
        plusCode.setText(PlusCode.encode(lat, lon, 11));
        voiceCode.setText(VoiceCode.encode(lat, lon, VoiceCode.Tier.GLOBAL));
        textCodeStandard.setText(TextCode.encode(lat, lon, TextCode.Tier.GLOBAL_STANDARD));
        textCodePrecise.setText(TextCode.encode(lat, lon, TextCode.Tier.GLOBAL_PRECISE));
    }

    /** A row whose value is copied to the clipboard when tapped. */
    private TextView copyRow(int labelRes) {
        TextView value = row(labelRes);
        value.setBackgroundResource(android.R.drawable.list_selector_background);
        value.setOnClickListener(v -> {
            CharSequence text = value.getText();
            if (text.length() == 0 || Format.NONE.contentEquals(text)) return;
            String plain = text.toString().split("\n")[0];
            if (plain.startsWith("(")) return; // "outside Great Britain" and similar notes
            ClipboardManager cm = context.getSystemService(ClipboardManager.class);
            cm.setPrimaryClip(ClipData.newPlainText(context.getString(labelRes), plain));
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                Toast.makeText(context, context.getString(R.string.toast_copied_value, plain), Toast.LENGTH_SHORT).show();
            }
        });
        return value;
    }

    private String stateText(GnssMonitor m, boolean fresh, int fixType) {
        if (!m.isRunning()) return context.getString(R.string.state_stopped);
        if (!m.isProviderEnabled()) return context.getString(R.string.state_gps_off);
        if (!fresh) return context.getString(R.string.state_searching);
        if (fixType == NmeaFix.FIX_TYPE_3D) return context.getString(R.string.state_fix_3d);
        if (fixType == NmeaFix.FIX_TYPE_2D) return context.getString(R.string.state_fix_2d);
        return context.getString(R.string.state_fix);
    }

    /** MSL altitude from the platform (API 34+) or from the NMEA GGA sentence. */
    private static double mslAltitude(Location loc, NmeaFix nmea, boolean nmeaFresh) {
        if (loc != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE
                && loc.hasMslAltitude()) {
            return loc.getMslAltitudeMeters();
        }
        return nmeaFresh ? nmea.getMslAltitude() : Double.NaN;
    }

    private void renderConstellations(GnssMonitor m) {
        constellationRows.removeAllViews();
        if (m.getConstellationCounts().isEmpty()) {
            TextView empty = new TextView(context);
            empty.setText(R.string.none_in_view);
            empty.setTextColor(context.getColor(R.color.text_secondary));
            empty.setPadding(Ui.dp(context, 16), Ui.dp(context, 6), Ui.dp(context, 16), Ui.dp(context, 6));
            constellationRows.addView(empty);
            return;
        }
        for (ConstellationCount c : m.getConstellationCounts()) {
            String label = Constellations.name(c.constellation);
            String operator = Constellations.operator(c.constellation);
            if (!operator.isEmpty()) label += " (" + operator + ")";
            TextView value = addRow(constellationRows, label);
            value.setText(String.format(Locale.ROOT, "%d / %d", c.used, c.inView));
            value.setTextColor(Constellations.color(c.constellation));
        }
    }

    // ---- Builders ---------------------------------------------------------------------------

    private void section(int titleRes) {
        TextView title = new TextView(context);
        title.setText(titleRes);
        title.setAllCaps(true);
        title.setTextColor(context.getColor(R.color.accent));
        title.setTextSize(12);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setLetterSpacing(0.08f);
        title.setPadding(Ui.dp(context, 16), Ui.dp(context, 20), Ui.dp(context, 16), Ui.dp(context, 6));
        list.addView(title);
    }

    private TextView row(int labelRes) {
        return addRow(list, context.getString(labelRes));
    }

    private TextView addRow(LinearLayout parent, String labelText) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(Ui.dp(context, 36));
        row.setPadding(Ui.dp(context, 16), Ui.dp(context, 4), Ui.dp(context, 16), Ui.dp(context, 4));

        TextView label = new TextView(context);
        label.setText(labelText);
        label.setTextColor(context.getColor(R.color.text_secondary));
        label.setTextSize(14);
        row.addView(label, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView value = new TextView(context);
        value.setText(Format.NONE);
        value.setTextColor(context.getColor(R.color.text_primary));
        value.setTextSize(15);
        value.setGravity(Gravity.END);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMarginStart(Ui.dp(context, 12));
        row.addView(value, lp);

        parent.addView(row);
        return value;
    }
}

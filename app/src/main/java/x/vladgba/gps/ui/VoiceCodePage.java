package x.vladgba.gps.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.location.Location;
import android.os.Build;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import x.vladgba.gps.R;
import x.vladgba.gps.coords.CoordinateParser;
import x.vladgba.gps.coords.TextCode;
import x.vladgba.gps.coords.VoiceCode;
import x.vladgba.gps.gnss.GnssMonitor;
import x.vladgba.gps.nav.Waypoint;
import x.vladgba.gps.nav.WaypointStore;
import x.vladgba.gps.qr.QrCode;

import java.util.Locale;

/**
 * Location codes: the current position (or a waypoint) as compact text codes for messages and
 * as speakable voice codes, with copy, share and QR; plus a decoder for received codes.
 */
final class VoiceCodePage implements Page {

    /** Short-code results further out than this share of the window get a warning. */
    private static final double EDGE_FRACTION = 0.75;
    private static final String PREFS = "codes";
    private static final String KEY_PRECISE = "precise";
    private static final TextCode.Range[] TEXT_RANGES = {TextCode.Range.GLOBAL, TextCode.Range.REGIONAL, TextCode.Range.LOCAL};

    private final Activity activity;
    private final SharedPreferences prefs;
    private final ScrollView root;
    private final TextView sourceTitle;
    private final Button sourceReset;
    private final TextView standardChip;
    private final TextView preciseChip;
    private final TextView[] textCodes = new TextView[TEXT_RANGES.length];
    private final TextView[] textRanges = new TextView[TEXT_RANGES.length];
    private final TextView[] codes = new TextView[VoiceCode.Tier.values().length];
    private final TextView[] spoken = new TextView[VoiceCode.Tier.values().length];
    private final TextView[] ranges = new TextView[VoiceCode.Tier.values().length];
    private final EditText input;
    private final TextView result;
    private final LinearLayout resultActions;
    private GnssMonitor monitor;
    /** Waypoint shared instead of the current position, or null. */
    private Waypoint source;
    private double[] decoded;

    VoiceCodePage(Activity activity) {
        this.activity = activity;
        prefs = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        root = new ScrollView(activity);
        LinearLayout list = new LinearLayout(activity);
        list.setOrientation(LinearLayout.VERTICAL);
        int pad = Ui.dp(activity, 16);
        list.setPadding(pad, pad, pad, pad);
        root.addView(list);

        LinearLayout header = new LinearLayout(activity);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        sourceTitle = text(15, R.color.text_primary);
        sourceTitle.setTypeface(Typeface.DEFAULT_BOLD);
        header.addView(sourceTitle, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        sourceReset = borderless(R.string.vc_use_my_position, v -> setSource(null));
        header.addView(sourceReset);
        list.addView(header);

        // ---- Text codes (messages) ----
        list.addView(groupTitle(R.string.tc_title));
        LinearLayout chips = new LinearLayout(activity);
        chips.setOrientation(LinearLayout.HORIZONTAL);
        chips.setGravity(Gravity.CENTER_VERTICAL);
        TextView precisionLabel = text(13, R.color.text_secondary);
        precisionLabel.setText(R.string.tc_precision);
        chips.addView(precisionLabel);
        standardChip = chip(R.string.tc_standard, v -> setPrecise(false));
        preciseChip = chip(R.string.tc_precise, v -> setPrecise(true));
        chips.addView(standardChip);
        chips.addView(preciseChip);
        list.addView(chips);
        int[] textTitles = {R.string.vc_global, R.string.vc_regional, R.string.vc_local};
        for (int i = 0; i < TEXT_RANGES.length; i++) {
            final TextCode.Range range = TEXT_RANGES[i];
            list.addView(section(textTitles[i]));
            textCodes[i] = text(26, R.color.text_primary);
            textCodes[i].setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
            textCodes[i].setTextIsSelectable(true);
            list.addView(textCodes[i]);
            textRanges[i] = text(12, R.color.text_secondary);
            list.addView(textRanges[i]);
            list.addView(actions(v -> copy(textCode(range)), v -> shareText(textCode(range)),
                    v -> showQr(textCode(range), textCode(range))));
        }

        // ---- Voice codes (speaking) ----
        list.addView(groupTitle(R.string.vc_title));
        VoiceCode.Tier[] tiers = VoiceCode.Tier.values();
        int[] titles = {R.string.vc_global, R.string.vc_regional, R.string.vc_local};
        for (int i = 0; i < tiers.length; i++) {
            final VoiceCode.Tier tier = tiers[i];
            list.addView(section(titles[i]));
            codes[i] = text(22, R.color.text_primary);
            codes[i].setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
            codes[i].setTextIsSelectable(true);
            list.addView(codes[i]);
            spoken[i] = text(15, R.color.accent);
            list.addView(spoken[i]);
            ranges[i] = text(12, R.color.text_secondary);
            list.addView(ranges[i]);
            list.addView(actions(v -> copy(voiceCode(tier)), v -> shareVoice(tier),
                    v -> showQr(voiceCode(tier), voiceCode(tier) != null ? VoiceCode.spoken(voiceCode(tier)) : null)));
        }

        // ---- Decoder ----
        list.addView(groupTitle(R.string.vc_decode_title));
        input = new EditText(activity);
        input.setHint(R.string.vc_decode_hint);
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        input.setTextColor(activity.getColor(R.color.text_primary));
        list.addView(input);
        list.addView(borderless(R.string.vc_decode, v -> decode()));
        result = text(14, R.color.text_primary);
        result.setTypeface(Typeface.MONOSPACE);
        result.setTextIsSelectable(true);
        list.addView(result);
        resultActions = new LinearLayout(activity);
        resultActions.setOrientation(LinearLayout.HORIZONTAL);
        resultActions.addView(borderless(R.string.vc_save_waypoint, v -> saveDecoded(false)));
        resultActions.addView(borderless(R.string.wp_navigate, v -> saveDecoded(true)));
        resultActions.setVisibility(View.GONE);
        list.addView(resultActions);

        TextView about = text(12, R.color.text_secondary);
        about.setText(R.string.vc_about);
        about.setPadding(0, Ui.dp(activity, 16), 0, 0);
        list.addView(about);

        selectPrecisionChip();
    }

    /** Shares this waypoint instead of the current position (null = current position). */
    void setSource(Waypoint waypoint) {
        source = waypoint;
        if (monitor != null) update(monitor);
    }

    /** Pre-fills the decoder, e.g. with text shared to the app, and decodes it. */
    void setInput(String text) {
        input.setText(text);
        decode();
        root.post(() -> root.smoothScrollTo(0, input.getTop()));
    }

    @Override
    public View getView() {
        return root;
    }

    @Override
    public void update(GnssMonitor m) {
        monitor = m;
        double[] p = sourcePosition();
        sourceTitle.setText(source != null ? activity.getString(R.string.vc_source_waypoint, source.name)
                : activity.getString(R.string.vc_source_me));
        sourceReset.setVisibility(source != null ? View.VISIBLE : View.GONE);
        double lat = p != null ? p[0] : 45;

        for (int i = 0; i < TEXT_RANGES.length; i++) {
            TextCode.Tier tier = TextCode.Tier.of(TEXT_RANGES[i], precision());
            textCodes[i].setText(p == null ? Format.NONE : TextCode.encode(p[0], p[1], tier));
            textRanges[i].setText(tier.range == TextCode.Range.GLOBAL
                    ? activity.getString(R.string.tc_range_global, tier.chars, Format.meters(tier.cellMeters()))
                    : activity.getString(R.string.tc_range_short, tier.chars, Format.meters(tier.cellMeters()),
                    Format.distance(tier.rangeMeters()), Format.distance(tier.rangeMetersEastWest(lat))));
        }

        VoiceCode.Tier[] tiers = VoiceCode.Tier.values();
        for (int i = 0; i < tiers.length; i++) {
            if (p == null) {
                codes[i].setText(Format.NONE);
                spoken[i].setText("");
            } else {
                String c = VoiceCode.encode(p[0], p[1], tiers[i]);
                codes[i].setText(c);
                spoken[i].setText(VoiceCode.spoken(c));
            }
            ranges[i].setText(tiers[i] == VoiceCode.Tier.GLOBAL
                    ? activity.getString(R.string.vc_range_global, tiers[i].syllables)
                    : activity.getString(R.string.vc_range_short, tiers[i].syllables,
                    Format.distance(tiers[i].rangeMeters()), Format.distance(tiers[i].rangeMetersEastWest(lat))));
        }
    }

    // ---- Precision ----------------------------------------------------------------------------

    private TextCode.Precision precision() {
        return prefs.getBoolean(KEY_PRECISE, false) ? TextCode.Precision.PRECISE : TextCode.Precision.STANDARD;
    }

    private void setPrecise(boolean precise) {
        prefs.edit().putBoolean(KEY_PRECISE, precise).apply();
        selectPrecisionChip();
        if (monitor != null) update(monitor);
    }

    private void selectPrecisionChip() {
        boolean precise = precision() == TextCode.Precision.PRECISE;
        standardChip.setSelected(!precise);
        preciseChip.setSelected(precise);
    }

    // ---- Codes and actions --------------------------------------------------------------------

    private double[] sourcePosition() {
        if (source != null) return new double[]{source.latitude, source.longitude};
        Location loc = monitor != null ? monitor.getLocation() : null;
        return loc != null ? new double[]{loc.getLatitude(), loc.getLongitude()} : null;
    }

    private String textCode(TextCode.Range range) {
        double[] p = sourcePosition();
        return p == null ? null : TextCode.encode(p[0], p[1], TextCode.Tier.of(range, precision()));
    }

    private String voiceCode(VoiceCode.Tier tier) {
        double[] p = sourcePosition();
        return p == null ? null : VoiceCode.encode(p[0], p[1], tier);
    }

    private boolean noPosition(String code) {
        if (code != null) return false;
        Toast.makeText(activity, R.string.toast_no_position, Toast.LENGTH_SHORT).show();
        return true;
    }

    private void copy(String code) {
        if (noPosition(code)) return;
        ClipboardManager cm = activity.getSystemService(ClipboardManager.class);
        cm.setPrimaryClip(ClipData.newPlainText("location code", code));
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            Toast.makeText(activity, activity.getString(R.string.toast_copied_value, code), Toast.LENGTH_SHORT).show();
        }
    }

    /** Text codes are shared bare: as compact as possible in a message. */
    private void shareText(String code) {
        if (noPosition(code)) return;
        send(code);
    }

    private void shareVoice(VoiceCode.Tier tier) {
        String c = voiceCode(tier);
        if (noPosition(c)) return;
        double[] p = sourcePosition();
        send(activity.getString(tier == VoiceCode.Tier.GLOBAL ? R.string.vc_share_text_global : R.string.vc_share_text_short,
                c, VoiceCode.spoken(c), Format.coordinates(p[0], p[1])));
    }

    private void send(String text) {
        Intent send = new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text);
        try {
            activity.startActivity(Intent.createChooser(send, activity.getString(R.string.vc_share)));
        } catch (ActivityNotFoundException e) {
            Toast.makeText(activity, R.string.toast_no_app, Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * QR with a standard geo: URI (any camera opens it in a map) whose label carries the code,
     * so this app can read the code back when the URI is opened with it.
     */
    private void showQr(String code, String caption) {
        if (noPosition(code)) return;
        double[] p = sourcePosition();
        String uri = String.format(Locale.ROOT, "geo:%1$.7f,%2$.7f?q=%1$.7f,%2$.7f(%3$s)", p[0], p[1], code);
        QrView qr = new QrView(activity);
        qr.setCode(QrCode.encode(uri));
        LinearLayout box = new LinearLayout(activity);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        int pad = Ui.dp(activity, 16);
        box.setPadding(pad, pad, pad, 0);
        box.addView(qr, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        TextView captionView = text(18, R.color.text_primary);
        captionView.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        captionView.setGravity(Gravity.CENTER);
        captionView.setText(caption);
        captionView.setPadding(0, Ui.dp(activity, 12), 0, 0);
        box.addView(captionView);
        TextView hint = text(12, R.color.text_secondary);
        hint.setGravity(Gravity.CENTER);
        hint.setText(R.string.vc_qr_hint);
        box.addView(hint);
        new AlertDialog.Builder(activity, android.R.style.Theme_Material_Dialog_Alert)
                .setView(box)
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    // ---- Decoding -----------------------------------------------------------------------------

    private void decode() {
        String text = input.getText().toString();
        decoded = null;
        resultActions.setVisibility(View.GONE);
        Location me = monitor != null ? monitor.getLocation() : null;
        double refLat = me != null ? me.getLatitude() : Double.NaN;
        double refLon = me != null ? me.getLongitude() : Double.NaN;
        if (text.trim().isEmpty()) {
            result.setText("");
            return;
        }
        String payload = extractPayload(text, refLat, refLon);
        String label;
        String warning = null;
        double[] p;

        VoiceCode.Decoded voice = VoiceCode.looksLike(payload) ? VoiceCode.decode(payload, refLat, refLon) : null;
        TextCode.Decoded textCode = voice != null && voice.ok() ? null : TextCode.decode(payload, refLat, refLon);
        if (voice != null && voice.ok()) {
            p = new double[]{voice.latitude, voice.longitude};
            label = activity.getString(R.string.vc_tier_label, tierName(voice.tier));
            if (voice.tier != VoiceCode.Tier.GLOBAL && VoiceCode.windowFraction(voice, refLat, refLon) > EDGE_FRACTION) {
                warning = activity.getString(R.string.vc_edge_warning, tierName(voice.tier), Format.distance(voice.tier.rangeMeters()));
            }
        } else if (textCode != null && textCode.ok()) {
            p = new double[]{textCode.latitude, textCode.longitude};
            label = activity.getString(R.string.tc_tier_label, rangeName(textCode.tier.range),
                    Format.meters(textCode.tier.cellMeters()));
            // A short code only locates the place within a window around the reader; near the
            // window's edge the sender may in fact be beyond it (another place).
            if (textCode.tier.range != TextCode.Range.GLOBAL && TextCode.windowFraction(textCode, refLat, refLon) > EDGE_FRACTION) {
                warning = activity.getString(R.string.vc_edge_warning, rangeName(textCode.tier.range),
                        Format.distance(textCode.tier.rangeMeters()));
            }
        } else {
            p = CoordinateParser.parse(payload, refLat, refLon);
            label = activity.getString(R.string.vc_any_format);
            if (p == null) {
                // Explain the most likely failure: a voice-code shape, then a text-code shape.
                String error;
                if (voice != null) error = voiceError(voice.error);
                else if (textCode != null && TextCode.looksLike(payload)) error = textError(textCode.error);
                else error = activity.getString(R.string.vc_not_recognised);
                result.setText(error);
                result.setTextColor(activity.getColor(R.color.state_bad));
                return;
            }
        }
        decoded = p;
        StringBuilder sb = new StringBuilder(label).append('\n');
        sb.append(Format.coordinates(p[0], p[1])).append('\n');
        sb.append(Format.dms(p[0], true)).append("  ").append(Format.dms(p[1], false));
        if (me != null) {
            double dist = Waypoint.distance(me.getLatitude(), me.getLongitude(), p[0], p[1]);
            double bearing = Waypoint.bearing(me.getLatitude(), me.getLongitude(), p[0], p[1]);
            sb.append('\n').append(activity.getString(R.string.vc_from_me, Format.distance(dist), Format.bearing(bearing)));
        }
        if (warning != null) sb.append("\n\n").append(warning);
        result.setText(sb);
        result.setTextColor(activity.getColor(warning != null ? R.color.state_warn : R.color.text_primary));
        resultActions.setVisibility(View.VISIBLE);
    }

    /**
     * Finds the code or coordinates inside a longer message. Ordinary words can consist of code
     * characters ("Cathedral"), so a token is taken when its checksum passes; failing that, the
     * first token shaped like a code (for a useful error), then any line with coordinates.
     */
    static String extractPayload(String text, double refLat, double refLon) {
        String[] tokens = text.split("[\\s\\n]+");
        String shaped = null;
        for (String token : tokens) {
            String t = token.replaceAll("^[(\\[\"']+|[)\\]\"',.;:!?]+$", "");
            if (t.toLowerCase(Locale.ROOT).startsWith("geo:")) return t;
            if (VoiceCode.looksLike(t)) {
                if (VoiceCode.decode(t, refLat, refLon).ok()) return t;
                if (shaped == null) shaped = t;
            } else if (TextCode.looksLike(t)) {
                TextCode.Decoded d = TextCode.decode(t, refLat, refLon);
                if (d.ok()) return t;
                if (shaped == null && hasDigitAndLetter(t)) shaped = t;
            }
        }
        for (String line : text.split("\\n")) {
            if (CoordinateParser.parse(line.trim(), refLat, refLon) != null) return line.trim();
        }
        return shaped != null ? shaped : text;
    }

    private static boolean hasDigitAndLetter(String t) {
        boolean digit = false;
        boolean letter = false;
        for (char c : t.toCharArray()) {
            if (Character.isDigit(c)) digit = true;
            else if (Character.isLetter(c)) letter = true;
        }
        return digit && letter;
    }

    private String voiceError(VoiceCode.Error error) {
        switch (error) {
            case CHECKSUM: return activity.getString(R.string.vc_err_checksum);
            case WRONG_LENGTH: return activity.getString(R.string.vc_err_length);
            case NEEDS_REFERENCE: return activity.getString(R.string.vc_err_reference);
            case OUT_OF_RANGE: return activity.getString(R.string.vc_err_range);
            default: return activity.getString(R.string.vc_err_syllables);
        }
    }

    private String textError(String error) {
        switch (error) {
            case TextCode.ERROR_CHECKSUM: return activity.getString(R.string.tc_err_checksum);
            case TextCode.ERROR_LENGTH: return activity.getString(R.string.tc_err_length);
            case TextCode.ERROR_REFERENCE: return activity.getString(R.string.vc_err_reference);
            case TextCode.ERROR_RANGE: return activity.getString(R.string.vc_err_range);
            default: return activity.getString(R.string.tc_err_characters);
        }
    }

    private String tierName(VoiceCode.Tier tier) {
        switch (tier) {
            case GLOBAL: return activity.getString(R.string.vc_global);
            case REGIONAL: return activity.getString(R.string.vc_regional);
            default: return activity.getString(R.string.vc_local);
        }
    }

    private String rangeName(TextCode.Range range) {
        switch (range) {
            case GLOBAL: return activity.getString(R.string.vc_global);
            case REGIONAL: return activity.getString(R.string.vc_regional);
            default: return activity.getString(R.string.vc_local);
        }
    }

    private void saveDecoded(boolean navigate) {
        if (decoded == null) return;
        WaypointStore store = WaypointStore.get(activity);
        String code = TextCode.encode(decoded[0], decoded[1], TextCode.Tier.GLOBAL_STANDARD);
        Waypoint w = store.add(activity.getString(R.string.vc_waypoint_name, code), decoded[0], decoded[1], Double.NaN);
        if (navigate) store.setTarget(w);
        Toast.makeText(activity, navigate ? activity.getString(R.string.wp_target, w.name) : activity.getString(R.string.avg_saved),
                Toast.LENGTH_SHORT).show();
    }

    // ---- Builders ---------------------------------------------------------------------------

    private LinearLayout actions(View.OnClickListener copy, View.OnClickListener share, View.OnClickListener qr) {
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.addView(borderless(R.string.vc_copy, copy));
        row.addView(borderless(R.string.vc_share, share));
        row.addView(borderless(R.string.vc_qr, qr));
        return row;
    }

    private TextView chip(int textRes, View.OnClickListener l) {
        TextView chip = new TextView(activity, null, 0, R.style.SortChip);
        chip.setText(textRes);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, Ui.dp(activity, 32));
        lp.setMarginStart(Ui.dp(activity, 8));
        chip.setLayoutParams(lp);
        chip.setOnClickListener(l);
        return chip;
    }

    private Button borderless(int textRes, View.OnClickListener l) {
        Button b = new Button(activity, null, android.R.attr.borderlessButtonStyle);
        b.setText(textRes);
        b.setTextColor(activity.getColor(R.color.accent));
        b.setOnClickListener(l);
        return b;
    }

    private TextView groupTitle(int res) {
        TextView t = text(18, R.color.text_primary);
        t.setText(res);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setPadding(0, Ui.dp(activity, 20), 0, Ui.dp(activity, 4));
        return t;
    }

    private TextView section(int res) {
        TextView t = text(12, R.color.accent);
        t.setText(res);
        t.setAllCaps(true);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setLetterSpacing(0.08f);
        t.setPadding(0, Ui.dp(activity, 12), 0, Ui.dp(activity, 2));
        return t;
    }

    private TextView text(float sp, int color) {
        TextView t = new TextView(activity);
        t.setTextSize(sp);
        t.setTextColor(activity.getColor(color));
        return t;
    }
}

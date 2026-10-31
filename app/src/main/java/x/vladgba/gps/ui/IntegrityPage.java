package x.vladgba.gps.ui;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.location.GnssMeasurementsEvent;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import x.vladgba.gps.R;
import x.vladgba.gps.gnss.GnssMonitor;
import x.vladgba.gps.gnss.IntegrityReport;
import x.vladgba.gps.gnss.IntegrityReport.Check;
import x.vladgba.gps.gnss.IntegrityReport.CheckId;
import x.vladgba.gps.gnss.IntegrityReport.Level;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/** Jamming and spoofing verdicts, a short history chart and every individual check. */
public final class IntegrityPage implements Page {

    private final Context context;
    private final ScrollView root;
    private final Verdict jamming;
    private final Verdict spoofing;
    private final TextView measurementsNote;
    private final IntegrityChartView chart;
    private final TextView chartLegend;
    private final Map<CheckId, CheckRow> rows = new EnumMap<>(CheckId.class);
    private GnssMonitor monitor;

    public IntegrityPage(Context context) {
        this.context = context;
        root = new ScrollView(context);
        LinearLayout list = new LinearLayout(context);
        list.setOrientation(LinearLayout.VERTICAL);
        int pad = Ui.dp(context, 16);
        list.setPadding(pad, Ui.dp(context, 12), pad, pad);
        root.addView(list);

        LinearLayout verdicts = new LinearLayout(context);
        verdicts.setOrientation(LinearLayout.HORIZONTAL);
        jamming = new Verdict(R.string.verdict_jamming);
        spoofing = new Verdict(R.string.verdict_spoofing);
        LinearLayout.LayoutParams left = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        left.setMarginEnd(Ui.dp(context, 6));
        LinearLayout.LayoutParams right = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        right.setMarginStart(Ui.dp(context, 6));
        verdicts.addView(jamming.view, left);
        verdicts.addView(spoofing.view, right);
        list.addView(verdicts);

        measurementsNote = text(R.string.measurements_not_supported, R.color.state_warn, 13);
        measurementsNote.setPadding(0, Ui.dp(context, 10), 0, 0);
        measurementsNote.setVisibility(View.GONE);
        list.addView(measurementsNote);

        list.addView(sectionTitle(R.string.section_history));
        chart = new IntegrityChartView(context);
        list.addView(chart, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, Ui.dp(context, 150)));
        chartLegend = new TextView(context);
        chartLegend.setTextSize(12);
        chartLegend.setGravity(Gravity.CENTER);
        list.addView(chartLegend);

        list.addView(sectionTitle(R.string.section_jamming_checks));
        addRow(list, CheckId.AGC, R.string.check_agc, R.string.check_agc_desc);
        addRow(list, CheckId.CN0_DROP, R.string.check_cn0_drop, R.string.check_cn0_drop_desc);
        addRow(list, CheckId.SIGNAL_LOSS, R.string.check_signal_loss, R.string.check_signal_loss_desc);

        list.addView(sectionTitle(R.string.section_spoofing_checks));
        addRow(list, CheckId.MOCK_LOCATION, R.string.check_mock, R.string.check_mock_desc);
        addRow(list, CheckId.NETWORK_DISTANCE, R.string.check_network, R.string.check_network_desc);
        addRow(list, CheckId.POSITION_JUMP, R.string.check_jump, R.string.check_jump_desc);
        addRow(list, CheckId.CLOCK, R.string.check_clock, R.string.check_clock_desc);
        addRow(list, CheckId.CN0_UNIFORM, R.string.check_uniform, R.string.check_uniform_desc);
        addRow(list, CheckId.CN0_ELEVATION, R.string.check_elevation, R.string.check_elevation_desc);
        addRow(list, CheckId.CN0_STRONG, R.string.check_strong, R.string.check_strong_desc);
        addRow(list, CheckId.CONSTELLATION_LOSS, R.string.check_constellations, R.string.check_constellations_desc);

        Button reset = new Button(context, null, android.R.attr.borderlessButtonStyle);
        reset.setText(R.string.integrity_reset);
        reset.setTextColor(context.getColor(R.color.accent));
        reset.setOnClickListener(v -> {
            if (monitor == null) return;
            monitor.getIntegrityAnalyzer().reset();
            Toast.makeText(context, R.string.integrity_reset_done, Toast.LENGTH_SHORT).show();
            update(monitor);
        });
        LinearLayout.LayoutParams resetLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        resetLp.topMargin = Ui.dp(context, 12);
        list.addView(reset, resetLp);

        TextView disclaimer = text(R.string.integrity_disclaimer, R.color.text_secondary, 12);
        disclaimer.setPadding(0, Ui.dp(context, 8), 0, 0);
        list.addView(disclaimer);
    }

    @Override
    public View getView() {
        return root;
    }

    @Override
    public void update(GnssMonitor m) {
        monitor = m;
        IntegrityReport report = m.getIntegrityReport();
        jamming.set(report.jamming, R.string.verdict_jamming_warn, R.string.verdict_jamming_alert);
        spoofing.set(report.spoofing, R.string.verdict_spoofing_warn, R.string.verdict_spoofing_alert);
        bind(report.jammingChecks);
        bind(report.spoofingChecks);

        measurementsNote.setVisibility(
                m.getMeasurementsStatus() == GnssMeasurementsEvent.Callback.STATUS_NOT_SUPPORTED ? View.VISIBLE : View.GONE);

        String band = m.getIntegrityAnalyzer().getAgcHistoryBand();
        chart.setSeries(m.getIntegrityAnalyzer().getCn0History(),
                band != null ? m.getIntegrityAnalyzer().getAgcHistory() : null);
        SpannableStringBuilder legend = new SpannableStringBuilder();
        appendLegend(legend, IntegrityChartView.CN0_COLOR, context.getString(R.string.chart_legend));
        if (band != null) {
            legend.append("    ");
            appendLegend(legend, IntegrityChartView.AGC_COLOR, context.getString(R.string.chart_legend_agc, band));
        }
        chartLegend.setText(legend);
    }

    private void appendLegend(SpannableStringBuilder sb, int color, String label) {
        int start = sb.length();
        sb.append("━ ").append(label);
        sb.setSpan(new ForegroundColorSpan(color), start, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    }

    private void bind(List<Check> checks) {
        for (Check c : checks) {
            CheckRow row = rows.get(c.id);
            if (row == null) continue;
            row.dot.setTextColor(levelColor(c.level));
            row.value.setText(c.value != null ? c.value : Format.NONE);
            row.value.setTextColor(c.level == Level.WARN || c.level == Level.ALERT
                    ? levelColor(c.level) : context.getColor(R.color.text_primary));
        }
    }

    private int levelColor(Level level) {
        switch (level) {
            case OK: return context.getColor(R.color.state_good);
            case WARN: return context.getColor(R.color.state_warn);
            case ALERT: return context.getColor(R.color.state_bad);
            default: return context.getColor(R.color.state_off);
        }
    }

    // ---- Builders ---------------------------------------------------------------------------

    private void addRow(LinearLayout parent, CheckId id, int titleRes, int descRes) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(0, Ui.dp(context, 8), 0, Ui.dp(context, 8));

        LinearLayout header = new LinearLayout(context);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView dot = new TextView(context);
        dot.setText("●");
        dot.setTextSize(14);
        dot.setPadding(0, 0, Ui.dp(context, 8), 0);
        header.addView(dot);
        TextView title = text(titleRes, R.color.text_primary, 15);
        header.addView(title, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(header);

        TextView value = new TextView(context);
        value.setTextSize(14);
        value.setTypeface(Typeface.DEFAULT_BOLD);
        value.setPadding(Ui.dp(context, 22), Ui.dp(context, 2), 0, 0);
        row.addView(value);

        TextView desc = text(descRes, R.color.text_secondary, 12);
        desc.setPadding(Ui.dp(context, 22), Ui.dp(context, 2), 0, 0);
        row.addView(desc);

        parent.addView(row);
        rows.put(id, new CheckRow(dot, value));
    }

    private TextView sectionTitle(int res) {
        TextView t = text(res, R.color.accent, 12);
        t.setAllCaps(true);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setLetterSpacing(0.08f);
        t.setPadding(0, Ui.dp(context, 20), 0, Ui.dp(context, 6));
        return t;
    }

    private TextView text(int res, int colorRes, float sizeSp) {
        TextView t = new TextView(context);
        t.setText(res);
        t.setTextColor(context.getColor(colorRes));
        t.setTextSize(sizeSp);
        return t;
    }

    private static final class CheckRow {
        final TextView dot;
        final TextView value;

        CheckRow(TextView dot, TextView value) {
            this.dot = dot;
            this.value = value;
        }
    }

    /** Colored card: "Jamming" over the verdict text. */
    private final class Verdict {
        final LinearLayout view;
        final TextView status;
        final GradientDrawable background = new GradientDrawable();

        Verdict(int titleRes) {
            view = new LinearLayout(context);
            view.setOrientation(LinearLayout.VERTICAL);
            int pad = Ui.dp(context, 14);
            view.setPadding(pad, pad, pad, pad);
            background.setCornerRadius(Ui.dp(context, 12));
            view.setBackground(background);
            TextView title = text(titleRes, R.color.text_primary, 13);
            title.setAllCaps(true);
            title.setLetterSpacing(0.08f);
            view.addView(title);
            status = new TextView(context);
            status.setTextSize(20);
            status.setTypeface(Typeface.DEFAULT_BOLD);
            status.setTextColor(0xFFFFFFFF);
            status.setPadding(0, Ui.dp(context, 4), 0, 0);
            view.addView(status);
        }

        void set(Level level, int warnRes, int alertRes) {
            int text;
            switch (level) {
                case OK: text = R.string.verdict_ok; break;
                case WARN: text = warnRes; break;
                case ALERT: text = alertRes; break;
                default: text = R.string.verdict_unknown; break;
            }
            status.setText(text);
            int color = levelColor(level);
            // Translucent fill with a solid border keeps the text readable on every state.
            background.setColor((color & 0x00FFFFFF) | 0x33000000);
            background.setStroke(Ui.dp(context, 1), color);
        }
    }
}

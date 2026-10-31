package x.vladgba.gps.ui;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import x.vladgba.gps.R;
import x.vladgba.gps.gnss.GnssMonitor;

/** Live raw NMEA log with pause and clear. Rendering is batched to a few times per second. */
public final class NmeaPage implements Page {

    private static final long RENDER_DELAY_MS = 300;

    private final Context context;
    private final LinearLayout root;
    private final ScrollView scroll;
    private final TextView log;
    private final TextView counter;
    private final Button pauseButton;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private GnssMonitor monitor;
    private boolean paused;
    private boolean renderPending;
    private final Runnable render = () -> {
        renderPending = false;
        render();
    };

    public NmeaPage(Context context) {
        this.context = context;
        root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);

        LinearLayout controls = new LinearLayout(context);
        controls.setOrientation(LinearLayout.HORIZONTAL);
        controls.setGravity(Gravity.CENTER_VERTICAL);
        controls.setPadding(Ui.dp(context, 8), 0, Ui.dp(context, 16), 0);

        pauseButton = new Button(context, null, android.R.attr.borderlessButtonStyle);
        pauseButton.setText(R.string.nmea_pause);
        pauseButton.setTextColor(context.getColor(R.color.accent));
        pauseButton.setOnClickListener(v -> setPaused(!paused));
        controls.addView(pauseButton);

        Button clearButton = new Button(context, null, android.R.attr.borderlessButtonStyle);
        clearButton.setText(R.string.nmea_clear);
        clearButton.setTextColor(context.getColor(R.color.accent));
        clearButton.setOnClickListener(v -> {
            if (monitor != null) monitor.clearNmeaLog();
            render();
        });
        controls.addView(clearButton);

        counter = new TextView(context);
        counter.setGravity(Gravity.END);
        counter.setTextColor(context.getColor(R.color.text_secondary));
        counter.setTextSize(12);
        controls.addView(counter, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        root.addView(controls);

        scroll = new ScrollView(context);
        log = new TextView(context);
        log.setTypeface(android.graphics.Typeface.MONOSPACE);
        log.setTextSize(11);
        log.setTextColor(context.getColor(R.color.text_primary));
        log.setTextIsSelectable(true);
        int pad = Ui.dp(context, 12);
        log.setPadding(pad, 0, pad, pad);
        scroll.addView(log);
        root.addView(scroll, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
    }

    private void setPaused(boolean value) {
        paused = value;
        pauseButton.setText(paused ? R.string.nmea_resume : R.string.nmea_pause);
        if (!paused) render();
    }

    @Override
    public View getView() {
        return root;
    }

    @Override
    public void update(GnssMonitor m) {
        // Also called when the tab is re-shown, so render rather than only refresh the counter.
        monitor = m;
        if (!renderPending) render();
    }

    @Override
    public void onNmeaMessage(GnssMonitor m, String sentence) {
        monitor = m;
        if (paused || renderPending) return;
        renderPending = true;
        handler.postDelayed(render, RENDER_DELAY_MS);
    }

    private void updateCounter() {
        if (monitor == null) return;
        counter.setText(context.getString(R.string.nmea_count,
                (int) Math.min(Integer.MAX_VALUE, monitor.getNmeaReceived()), monitor.getNmeaLog().size()));
    }

    private void render() {
        if (monitor == null) return;
        updateCounter();
        if (paused) return;
        if (monitor.getNmeaLog().isEmpty()) {
            log.setText(R.string.nmea_empty);
            return;
        }
        StringBuilder sb = new StringBuilder(monitor.getNmeaLog().size() * 80);
        for (String line : monitor.getNmeaLog()) sb.append(line).append('\n');
        log.setText(sb);
        scroll.post(() -> scroll.scrollTo(0, log.getBottom()));
    }
}

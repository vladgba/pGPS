package com.vladgba.gpstools.ui;

import android.app.Activity;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.vladgba.gpstools.R;
import com.vladgba.gpstools.gnss.GnssMonitor;
import com.vladgba.gpstools.record.RecorderService;

/** Tools tab: a menu of tools, each opening as a sub-page with a back header. */
public final class ToolsPage implements Page {

    private final Activity activity;
    private final Runnable onNavigationChanged;
    private final FrameLayout root;
    private final View menu;
    private final LinearLayout subPage;
    private final TextView subTitle;
    private final FrameLayout subBody;
    private final Page[] tools;
    private final int[] titles = {R.string.tool_recorder, R.string.tool_averaging, R.string.tool_history,
            R.string.tool_orbits, R.string.tool_waypoints, R.string.tool_ranging, R.string.tool_backtrack, R.string.tool_spoof, R.string.tool_voice_code, R.string.tool_nmea};
    private final int[] descriptions = {R.string.tool_recorder_desc, R.string.tool_averaging_desc,
            R.string.tool_history_desc, R.string.tool_orbits_desc, R.string.tool_waypoints_desc, R.string.tool_ranging_desc, R.string.tool_backtrack_desc, R.string.tool_spoof_desc, R.string.tool_voice_code_desc, R.string.tool_nmea_desc};
    private final TextView recorderSubtitle;
    private final VoiceCodePage voiceCodePage;
    private int current = -1;
    private GnssMonitor monitor;

    public ToolsPage(Activity activity, Runnable onNavigationChanged) {
        this.activity = activity;
        this.onNavigationChanged = onNavigationChanged;
        voiceCodePage = new VoiceCodePage(activity);
        tools = new Page[]{
                new RecorderPage(activity),
                new AveragingPage(activity),
                new HistoryPage(activity),
                new OrbitsPage(activity),
                new WaypointsPage(activity, this::shareWaypointAsVoiceCode),
                new RangingPage(activity),
                new BacktrackPage(activity),
                new SpoofPage(activity),
                voiceCodePage,
                new NmeaPage(activity)};

        root = new FrameLayout(activity);

        ScrollView scroll = new ScrollView(activity);
        LinearLayout list = new LinearLayout(activity);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(0, Ui.dp(activity, 8), 0, Ui.dp(activity, 8));
        scroll.addView(list);
        TextView firstSubtitle = null;
        for (int i = 0; i < tools.length; i++) {
            final int index = i;
            LinearLayout row = new LinearLayout(activity);
            row.setOrientation(LinearLayout.VERTICAL);
            row.setPadding(Ui.dp(activity, 16), Ui.dp(activity, 14), Ui.dp(activity, 16), Ui.dp(activity, 14));
            row.setBackgroundResource(android.R.drawable.list_selector_background);
            row.setClickable(true);
            row.setFocusable(true);
            row.setOnClickListener(v -> open(index));
            TextView title = new TextView(activity);
            title.setText(titles[i]);
            title.setTextSize(16);
            title.setTypeface(Typeface.DEFAULT_BOLD);
            title.setTextColor(activity.getColor(R.color.text_primary));
            row.addView(title);
            TextView desc = new TextView(activity);
            desc.setText(descriptions[i]);
            desc.setTextSize(13);
            desc.setTextColor(activity.getColor(R.color.text_secondary));
            row.addView(desc);
            if (i == 0) firstSubtitle = desc;
            list.addView(row);
            View divider = new View(activity);
            divider.setBackgroundColor(activity.getColor(R.color.divider));
            list.addView(divider, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1));
        }
        recorderSubtitle = firstSubtitle;
        menu = scroll;
        root.addView(menu);

        subPage = new LinearLayout(activity);
        subPage.setOrientation(LinearLayout.VERTICAL);
        LinearLayout header = new LinearLayout(activity);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        Button back = new Button(activity, null, android.R.attr.borderlessButtonStyle);
        back.setText(R.string.tools_back);
        back.setTextColor(activity.getColor(R.color.accent));
        back.setOnClickListener(v -> goBack());
        header.addView(back);
        subTitle = new TextView(activity);
        subTitle.setTextSize(16);
        subTitle.setTypeface(Typeface.DEFAULT_BOLD);
        subTitle.setTextColor(activity.getColor(R.color.text_primary));
        header.addView(subTitle);
        subPage.addView(header);
        View divider = new View(activity);
        divider.setBackgroundColor(activity.getColor(R.color.divider));
        subPage.addView(divider, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1));
        subBody = new FrameLayout(activity);
        subPage.addView(subBody, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        subPage.setVisibility(View.GONE);
        root.addView(subPage);
    }

    /** Opens the Voice code tool sharing this waypoint. */
    private void shareWaypointAsVoiceCode(com.vladgba.gpstools.nav.Waypoint waypoint) {
        voiceCodePage.setSource(waypoint);
        openPage(voiceCodePage);
    }

    /** Opens the Voice code tool with text received from another app (code, coordinates, geo:). */
    public void openVoiceCodeWithInput(String text) {
        openPage(voiceCodePage);
        voiceCodePage.setInput(text);
    }

    private void openPage(Page page) {
        for (int i = 0; i < tools.length; i++) {
            if (tools[i] == page) {
                if (current >= 0 && current != i) tools[current].onHidden();
                open(i);
                return;
            }
        }
    }

    private void open(int index) {
        current = index;
        subTitle.setText(titles[index]);
        subBody.removeAllViews();
        subBody.addView(tools[index].getView(), new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        menu.setVisibility(View.GONE);
        subPage.setVisibility(View.VISIBLE);
        if (monitor != null) tools[index].update(monitor);
        onNavigationChanged.run();
    }

    @Override
    public View getView() {
        return root;
    }

    @Override
    public void update(GnssMonitor m) {
        monitor = m;
        if (current >= 0) {
            tools[current].update(m);
        } else if (RecorderService.isRecording()) {
            recorderSubtitle.setText(activity.getString(R.string.recorder_status,
                    Format.duration(RecorderService.getElapsedMs()), RecorderService.getFixCount(),
                    RecorderService.getEpochCount(), RecorderService.getNmeaCount()));
            recorderSubtitle.setTextColor(activity.getColor(R.color.state_bad));
        } else {
            recorderSubtitle.setText(R.string.tool_recorder_desc);
            recorderSubtitle.setTextColor(activity.getColor(R.color.text_secondary));
        }
    }

    @Override
    public void onNmeaMessage(GnssMonitor m, String sentence) {
        if (current >= 0) tools[current].onNmeaMessage(m, sentence);
    }

    @Override
    public boolean canGoBack() {
        return current >= 0;
    }

    @Override
    public void goBack() {
        if (current < 0) return;
        if (tools[current].canGoBack()) {
            tools[current].goBack();
            return;
        }
        tools[current].onHidden();
        current = -1;
        subBody.removeAllViews();
        subPage.setVisibility(View.GONE);
        menu.setVisibility(View.VISIBLE);
        if (monitor != null) update(monitor);
        onNavigationChanged.run();
    }

    @Override
    public void onHidden() {
        if (current >= 0) tools[current].onHidden();
    }
}

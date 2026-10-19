package com.vladgba.gpstools.ui;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;

import com.vladgba.gpstools.R;
import com.vladgba.gpstools.gnss.Constellations;
import com.vladgba.gpstools.gnss.GnssMonitor;
import com.vladgba.gpstools.gnss.SatelliteInfo;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/** Every tracked signal with its id, band, C/N0, elevation, azimuth and data flags. */
public final class SatellitesPage implements Page {

    private final Context context;
    private final View root;
    private final TextView summary;
    private final TextView empty;
    private final TextView[] sortChips;
    private final SatelliteAdapter adapter;
    private Comparator<SatelliteInfo> order = SatelliteInfo.BY_SYSTEM;
    private GnssMonitor lastMonitor;

    @SuppressLint("InflateParams") // MainActivity attaches the page with explicit MATCH_PARENT params.
    public SatellitesPage(Context context) {
        this.context = context;
        root = LayoutInflater.from(context).inflate(R.layout.page_satellites, null, false);
        summary = root.findViewById(R.id.sats_summary);
        empty = root.findViewById(R.id.sats_empty);
        ListView list = root.findViewById(R.id.sats_list);
        adapter = new SatelliteAdapter(context);
        list.setAdapter(adapter);
        list.setEmptyView(empty);
        // Tap a satellite: its C/N0 and elevation over the last 30 minutes.
        list.setOnItemClickListener((parent, view, position, id) -> {
            if (lastMonitor != null && context instanceof android.app.Activity) {
                HistoryPage.showSatellite((android.app.Activity) context, lastMonitor, adapter.getItem(position));
            }
        });

        sortChips = new TextView[]{
                root.findViewById(R.id.sort_system),
                root.findViewById(R.id.sort_signal),
                root.findViewById(R.id.sort_elevation)};
        List<Comparator<SatelliteInfo>> orders = new ArrayList<>();
        orders.add(SatelliteInfo.BY_SYSTEM);
        orders.add(SatelliteInfo.BY_SIGNAL);
        orders.add(SatelliteInfo.BY_ELEVATION);
        for (int i = 0; i < sortChips.length; i++) {
            final int index = i;
            sortChips[i].setOnClickListener(v -> {
                order = orders.get(index);
                selectChip(index);
                if (lastMonitor != null) update(lastMonitor);
            });
        }
        selectChip(0);
    }

    private void selectChip(int index) {
        for (int i = 0; i < sortChips.length; i++) sortChips[i].setSelected(i == index);
    }

    @Override
    public View getView() {
        return root;
    }

    @Override
    public void update(GnssMonitor m) {
        lastMonitor = m;
        List<SatelliteInfo> sorted = new ArrayList<>(m.getSatellites());
        Collections.sort(sorted, order);
        adapter.setItems(sorted);
        summary.setText(context.getString(R.string.sats_summary,
                m.getSatellitesInView(), m.getSatellitesUsed(), sorted.size()));
        if (sorted.isEmpty()) {
            empty.setText(m.isRunning() ? R.string.waiting_satellites : R.string.state_stopped);
        }
    }

    private static final class SatelliteAdapter extends BaseAdapter {
        private final Context context;
        private final LayoutInflater inflater;
        private List<SatelliteInfo> items = Collections.emptyList();

        SatelliteAdapter(Context context) {
            this.context = context;
            this.inflater = LayoutInflater.from(context);
        }

        void setItems(List<SatelliteInfo> items) {
            this.items = items;
            notifyDataSetChanged();
        }

        @Override
        public int getCount() {
            return items.size();
        }

        @Override
        public SatelliteInfo getItem(int position) {
            return items.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            View view = convertView;
            Holder h;
            if (view == null) {
                view = inflater.inflate(R.layout.item_satellite, parent, false);
                h = new Holder(view);
                view.setTag(h);
            } else {
                h = (Holder) view.getTag();
            }
            bind(h, items.get(position));
            return view;
        }

        private void bind(Holder h, SatelliteInfo s) {
            int color = Constellations.color(s.constellation);
            h.color.setBackgroundColor(color);
            h.color.setAlpha(s.usedInFix ? 1f : 0.35f);

            StringBuilder name = new StringBuilder();
            name.append(s.rinexId()).append("  ").append(Constellations.name(s.constellation));
            if (s.constellation == android.location.GnssStatus.CONSTELLATION_SBAS) {
                String service = Constellations.sbasService(s.svid);
                if (service != null) name.append(" · ").append(service);
            }
            h.name.setText(name);
            h.name.setTypeface(s.usedInFix ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);

            StringBuilder details = new StringBuilder(Constellations.idLabel(s.constellation, s.svid));
            String band = s.band();
            if (band != null) details.append(" · ").append(band);
            if (s.usedInFix) details.append(" · ").append(context.getString(R.string.flag_used));
            if (s.hasEphemeris) details.append(" · ").append(context.getString(R.string.flag_ephemeris));
            if (s.hasAlmanac) details.append(" · ").append(context.getString(R.string.flag_almanac));
            h.details.setText(details);

            h.cn0.setText(Format.cn0(s.cn0));
            h.cn0Bar.setProgress(Math.round(Math.max(0f, s.cn0) * 10f));
            h.cn0Bar.setProgressTintList(ColorStateList.valueOf(Ui.signalColor(s.cn0)));

            h.position.setText(s.hasSkyPosition()
                    ? String.format(Locale.ROOT, "El %5.1f°\nAz %5.1f°", s.elevation, s.azimuth)
                    : Format.NONE);
        }
    }

    private static final class Holder {
        final View color;
        final TextView name;
        final TextView details;
        final TextView cn0;
        final ProgressBar cn0Bar;
        final TextView position;

        Holder(View v) {
            color = v.findViewById(R.id.sat_color);
            name = v.findViewById(R.id.sat_name);
            details = v.findViewById(R.id.sat_details);
            cn0 = v.findViewById(R.id.sat_cn0);
            cn0Bar = v.findViewById(R.id.sat_cn0_bar);
            position = v.findViewById(R.id.sat_position);
        }
    }
}

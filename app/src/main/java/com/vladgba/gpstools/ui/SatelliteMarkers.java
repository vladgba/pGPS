package com.vladgba.gpstools.ui;

import com.vladgba.gpstools.gnss.SatelliteInfo;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Collapses per-signal entries into one marker per satellite for the sky views. */
final class SatelliteMarkers {

    private SatelliteMarkers() {
    }

    /**
     * One entry per satellite with a known sky position: strongest signal kept, marked used if
     * any of its signals is used. Unused satellites come first so used ones draw on top.
     */
    static List<SatelliteInfo> merge(List<SatelliteInfo> satellites) {
        Map<Long, SatelliteInfo> best = new LinkedHashMap<>();
        Map<Long, Boolean> used = new LinkedHashMap<>();
        for (SatelliteInfo s : satellites) {
            if (!s.hasSkyPosition()) continue;
            SatelliteInfo current = best.get(s.key());
            if (current == null || s.cn0 > current.cn0) best.put(s.key(), s);
            if (s.usedInFix) used.put(s.key(), true);
        }
        List<SatelliteInfo> result = new ArrayList<>();
        List<SatelliteInfo> usedMarkers = new ArrayList<>();
        for (Map.Entry<Long, SatelliteInfo> e : best.entrySet()) {
            SatelliteInfo s = e.getValue();
            boolean isUsed = used.containsKey(e.getKey());
            if (isUsed != s.usedInFix) {
                s = new SatelliteInfo(s.constellation, s.svid, s.cn0, s.elevation, s.azimuth,
                        s.carrierHz, s.basebandCn0, isUsed, s.hasAlmanac, s.hasEphemeris);
            }
            if (isUsed) usedMarkers.add(s);
            else result.add(s);
        }
        result.addAll(usedMarkers);
        return result;
    }

    /** Band labels of every signal of one satellite, e.g. "L1, L5". */
    static String bands(List<SatelliteInfo> all, long key) {
        StringBuilder sb = new StringBuilder();
        for (SatelliteInfo s : all) {
            if (s.key() != key) continue;
            String band = s.band();
            if (band == null || sb.indexOf(band) >= 0) continue;
            if (sb.length() > 0) sb.append(", ");
            sb.append(band);
        }
        return sb.toString();
    }
}

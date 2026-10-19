package com.vladgba.gpstools.nav;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Waypoints and the current navigation target, persisted as JSON in SharedPreferences
 * (small lists; no database needed). Main thread only.
 */
public final class WaypointStore {

    public interface Listener {
        void onWaypointsChanged();
    }

    private static final String PREFS = "waypoints";
    private static final String KEY_LIST = "list";
    private static final String KEY_TARGET = "target";

    private static WaypointStore instance;

    public static synchronized WaypointStore get(Context context) {
        if (instance == null) instance = new WaypointStore(context.getApplicationContext());
        return instance;
    }

    private final SharedPreferences prefs;
    private final List<Listener> listeners = new ArrayList<>();
    private List<Waypoint> waypoints;
    private long targetId;

    private WaypointStore(Context context) {
        prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        waypoints = load();
        targetId = prefs.getLong(KEY_TARGET, 0);
    }

    public void addListener(Listener l) {
        if (!listeners.contains(l)) listeners.add(l);
    }

    public void removeListener(Listener l) {
        listeners.remove(l);
    }

    public List<Waypoint> getAll() {
        return Collections.unmodifiableList(waypoints);
    }

    public Waypoint add(String name, double lat, double lon, double alt) {
        long now = System.currentTimeMillis();
        long id = now;
        for (Waypoint w : waypoints) if (w.id >= id) id = w.id + 1;
        Waypoint w = new Waypoint(id, name, lat, lon, alt, now);
        waypoints.add(w);
        save();
        return w;
    }

    public void delete(long id) {
        for (int i = 0; i < waypoints.size(); i++) {
            if (waypoints.get(i).id == id) {
                waypoints.remove(i);
                break;
            }
        }
        if (targetId == id) targetId = 0;
        save();
    }

    /** Navigation target, or null. */
    public Waypoint getTarget() {
        for (Waypoint w : waypoints) if (w.id == targetId) return w;
        return null;
    }

    public void setTarget(Waypoint w) {
        targetId = w != null ? w.id : 0;
        save();
    }

    private List<Waypoint> load() {
        List<Waypoint> list = new ArrayList<>();
        try {
            JSONArray array = new JSONArray(prefs.getString(KEY_LIST, "[]"));
            for (int i = 0; i < array.length(); i++) {
                JSONObject o = array.getJSONObject(i);
                list.add(new Waypoint(o.getLong("id"), o.getString("name"), o.getDouble("lat"),
                        o.getDouble("lon"), o.optDouble("alt", Double.NaN), o.optLong("created", 0)));
            }
        } catch (JSONException e) {
            // Corrupt storage: start empty rather than crash.
        }
        return list;
    }

    private void save() {
        JSONArray array = new JSONArray();
        try {
            for (Waypoint w : waypoints) {
                JSONObject o = new JSONObject();
                o.put("id", w.id);
                o.put("name", w.name);
                o.put("lat", w.latitude);
                o.put("lon", w.longitude);
                if (!Double.isNaN(w.altitude)) o.put("alt", w.altitude);
                o.put("created", w.createdAtMs);
                array.put(o);
            }
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
        prefs.edit().putString(KEY_LIST, array.toString()).putLong(KEY_TARGET, targetId).apply();
        for (Listener l : new ArrayList<>(listeners)) l.onWaypointsChanged();
    }
}

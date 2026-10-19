package com.vladgba.gpstools.nav;

import java.util.List;

/**
 * Guides back along a trail in reverse. Each update finds where the user is on the trail
 * (nearest segment, searched near the previous progress so loops and crossings do not make it
 * jump), then aims at a breadcrumb a little further towards the start so the arrow stays steady.
 * Pure logic: no Android types, so it can be tested off-device.
 */
public final class BacktrackNavigator {

    /** Aim this far ahead along the trail. */
    static final double LOOKAHEAD_M = 15;
    /** Within this distance of the start the user has arrived. */
    static final double ARRIVED_M = 10;
    /** How many segments behind/ahead of the current progress are searched. */
    static final int SEARCH_BACK = 60;
    static final int SEARCH_AHEAD = 8;

    public static final class Guidance {
        /** Breadcrumb being aimed at. */
        public final double targetLat;
        public final double targetLon;
        public final double distanceToTarget;
        public final double bearingToTarget;
        /** Distance still to walk: to the trail, then along it to the start. */
        public final double remaining;
        /** Distance from the trail line. */
        public final double offTrail;
        /** Share of the trail already walked back, 0..1. */
        public final double progress;
        public final boolean arrived;

        Guidance(double targetLat, double targetLon, double distanceToTarget, double bearingToTarget,
                 double remaining, double offTrail, double progress, boolean arrived) {
            this.targetLat = targetLat;
            this.targetLon = targetLon;
            this.distanceToTarget = distanceToTarget;
            this.bearingToTarget = bearingToTarget;
            this.remaining = remaining;
            this.offTrail = offTrail;
            this.progress = progress;
            this.arrived = arrived;
        }
    }

    private final double[] lats;
    private final double[] lons;
    /** cumulative[i] = distance along the trail from point 0 to point i. */
    private final double[] cumulative;
    /** Index of the segment (i-1 .. i) the user was last matched to; starts at the trail's end. */
    private int segment;

    public static BacktrackNavigator of(List<Trail.Point> trail) {
        double[] lats = new double[trail.size()];
        double[] lons = new double[trail.size()];
        for (int i = 0; i < lats.length; i++) {
            lats[i] = trail.get(i).latitude;
            lons[i] = trail.get(i).longitude;
        }
        return new BacktrackNavigator(lats, lons);
    }

    /** @param lats breadcrumb latitudes, start of the trail first */
    public BacktrackNavigator(double[] lats, double[] lons) {
        int n = lats.length;
        this.lats = lats.clone();
        this.lons = lons.clone();
        cumulative = new double[n];
        for (int i = 1; i < n; i++) {
            cumulative[i] = cumulative[i - 1] + Waypoint.distance(lats[i - 1], lons[i - 1], lats[i], lons[i]);
        }
        segment = Math.max(1, n - 1);
    }

    /** @return null when the trail has fewer than two points */
    public Guidance update(double lat, double lon) {
        int n = lats.length;
        if (n < 2) return null;

        // Nearest point on the trail around the last known progress.
        int from = Math.max(1, segment - SEARCH_BACK);
        int to = Math.min(n - 1, segment + SEARCH_AHEAD);
        double best = Double.POSITIVE_INFINITY;
        int bestSegment = segment;
        double bestAlong = 0; // distance along the trail from point 0 to the projection
        for (int i = from; i <= to; i++) {
            double[] proj = project(lat, lon, lats[i - 1], lons[i - 1], lats[i], lons[i]);
            if (proj[0] < best) {
                best = proj[0];
                bestSegment = i;
                bestAlong = cumulative[i - 1] + proj[1] * (cumulative[i] - cumulative[i - 1]);
            }
        }
        segment = bestSegment;

        // Aim at the first breadcrumb at least LOOKAHEAD_M closer to the start.
        double aimAlong = Math.max(0, bestAlong - LOOKAHEAD_M);
        int target = 0;
        for (int i = segment - 1; i >= 0; i--) {
            if (cumulative[i] <= aimAlong) {
                target = i;
                break;
            }
        }
        double dTarget = Waypoint.distance(lat, lon, lats[target], lons[target]);
        double toStart = Waypoint.distance(lat, lon, lats[0], lons[0]);
        boolean arrived = toStart <= ARRIVED_M || (bestAlong <= ARRIVED_M && best <= ARRIVED_M * 2);
        double remaining = best + bestAlong;
        double total = cumulative[n - 1];
        double progress = total > 0 ? 1 - bestAlong / total : 1;
        return new Guidance(lats[target], lons[target], dTarget,
                Waypoint.bearing(lat, lon, lats[target], lons[target]), remaining, best, progress, arrived);
    }

    /**
     * Distance from the point to segment a-b and the projection's position along the segment
     * (0 = a, 1 = b), on a local flat approximation (fine for segments of tens of metres).
     */
    static double[] project(double lat, double lon, double aLat, double aLon, double bLat, double bLon) {
        double k = Math.cos(Math.toRadians(lat));
        double ax = (aLon - lon) * k;
        double ay = aLat - lat;
        double bx = (bLon - lon) * k;
        double by = bLat - lat;
        double dx = bx - ax;
        double dy = by - ay;
        double len2 = dx * dx + dy * dy;
        double t = len2 == 0 ? 0 : Math.max(0, Math.min(1, -(ax * dx + ay * dy) / len2));
        double px = ax + t * dx;
        double py = ay + t * dy;
        double meters = Math.sqrt(px * px + py * py) * 111_320;
        return new double[]{meters, t};
    }
}

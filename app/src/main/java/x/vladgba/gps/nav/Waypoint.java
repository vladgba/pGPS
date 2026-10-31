package x.vladgba.gps.nav;

/** A saved point. Altitude is ellipsoidal (WGS84), NaN when unknown. */
public final class Waypoint {

    private static final double EARTH_RADIUS_M = 6_371_008.8;

    public final long id;
    public final String name;
    public final double latitude;
    public final double longitude;
    public final double altitude;
    public final long createdAtMs;

    public Waypoint(long id, String name, double latitude, double longitude, double altitude, long createdAtMs) {
        this.id = id;
        this.name = name;
        this.latitude = latitude;
        this.longitude = longitude;
        this.altitude = altitude;
        this.createdAtMs = createdAtMs;
    }

    /** Great-circle distance in metres. */
    public static double distance(double lat1, double lon1, double lat2, double lon2) {
        double p1 = Math.toRadians(lat1);
        double p2 = Math.toRadians(lat2);
        double dp = p2 - p1;
        double dl = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dp / 2) * Math.sin(dp / 2)
                + Math.cos(p1) * Math.cos(p2) * Math.sin(dl / 2) * Math.sin(dl / 2);
        return 2 * EARTH_RADIUS_M * Math.asin(Math.min(1, Math.sqrt(a)));
    }

    /** Initial great-circle bearing from point 1 to point 2, degrees from true north. */
    public static double bearing(double lat1, double lon1, double lat2, double lon2) {
        double p1 = Math.toRadians(lat1);
        double p2 = Math.toRadians(lat2);
        double dl = Math.toRadians(lon2 - lon1);
        double y = Math.sin(dl) * Math.cos(p2);
        double x = Math.cos(p1) * Math.sin(p2) - Math.sin(p1) * Math.cos(p2) * Math.cos(dl);
        double deg = Math.toDegrees(Math.atan2(y, x));
        return (deg + 360) % 360;
    }
}

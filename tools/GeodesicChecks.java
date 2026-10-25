import com.vladgba.gpstools.nav.Geodesic;
import com.vladgba.gpstools.nav.Waypoint;

import java.util.Arrays;
import java.util.Collections;
import java.util.Random;

/** Independent numeric checks; compile with production Geodesic.java and Waypoint.java on JDK 17. */
public final class GeodesicChecks {
    private static int checks;

    private static void check(boolean condition, String description) {
        checks++;
        if (!condition) throw new AssertionError(description);
    }

    private static void near(double actual, double expected, double tolerance, String description) {
        check(Double.isFinite(actual) && Math.abs(actual - expected) <= tolerance,
                description + ": " + actual + " vs " + expected);
    }

    private static Geodesic.Ray ray(double latitude, double longitude, double azimuth) {
        return new Geodesic.Ray("station", latitude, longitude, azimuth);
    }

    public static void main(String[] args) {
        near(Geodesic.speedOfSound(0), 331.3, 1e-9, "sound speed at zero Celsius");
        near(Geodesic.acousticDistance(3, 20), 1029.644, 0.001, "three-second acoustic range");
        near(Geodesic.acousticDistance(-1, 20), 0, 0, "negative time");
        near(Geodesic.acousticDistance(Double.POSITIVE_INFINITY, 20), 0, 0, "infinite time");
        near(Geodesic.acousticDistance(1, Double.NaN), 0, 0, "invalid temperature");
        near(Geodesic.acousticDistance(Double.MAX_VALUE, 20), 0, 0, "overflowing acoustic range");
        near(Geodesic.wrapLongitude(-359.98), 0.02, 1e-9, "eastward date-line delta");
        near(Geodesic.wrapLongitude(359.98), -0.02, 1e-9, "westward date-line delta");

        double[] east = Geodesic.destination(0, 179.99, 90, 3000);
        check(east[1] < -179.9, "projection crosses date line");
        near(Waypoint.distance(0, 179.99, east[0], east[1]), 3000, 1e-6, "projected distance");
        double[] pole = Geodesic.destination(90, 10, 180, 1000);
        check(Double.isFinite(pole[0]) && Double.isFinite(pole[1]), "finite polar projection");
        try {
            Geodesic.destination(0, 0, Double.NaN, 10);
            throw new AssertionError("invalid projection accepted");
        } catch (IllegalArgumentException expected) { checks++; }

        Geodesic.Intersection cross = Geodesic.triangulate(Arrays.asList(
                ray(-0.01, -0.01, 45), ray(-0.01, 0.01, 315)));
        check(cross != null, "forward crossing");
        near(cross.latitude, 0, 1e-8, "crossing latitude");
        near(cross.longitude, 0, 1e-8, "crossing longitude");
        near(cross.rmsMissM, 0, 1e-6, "two-ray residual");
        near(cross.maxAngleDeg, 90, 1e-8, "perpendicular crossing angle");
        Geodesic.Intersection dateline = Geodesic.triangulate(Arrays.asList(
                ray(-0.01, 179.99, 45), ray(-0.01, -179.99, 315)));
        check(dateline != null, "date-line crossing exists");
        near(dateline.latitude, 0, 1e-8, "date-line crossing latitude");
        near(Math.abs(dateline.longitude), 180, 1e-8, "date-line crossing longitude");

        check(Geodesic.triangulate(Arrays.asList(ray(0, 0, 0), ray(0, 0.01, 0))) == null,
                "parallel rays rejected");
        check(Geodesic.triangulate(Arrays.asList(ray(0, 0, 0), ray(0, 0.01, 180))) == null,
                "anti-parallel rays rejected");
        check(Geodesic.triangulate(Arrays.asList(ray(-0.01, -0.01, 225), ray(-0.01, 0.01, 135))) == null,
                "backward crossing rejected");
        check(Geodesic.triangulate(Collections.singletonList(ray(0, 0, 0))) == null, "one ray rejected");
        check(Geodesic.triangulate(Arrays.asList(ray(0, 0, Double.NaN), ray(1, 1, 90))) == null,
                "non-finite bearing rejected");
        check(Geodesic.triangulate(Arrays.asList(ray(Double.NaN, 0, 0), ray(1, 1, 90))) == null,
                "non-finite station rejected");
        check(Geodesic.triangulate(Arrays.asList(ray(91, 0, 0), ray(1, 1, 90))) == null,
                "out-of-range station rejected");
        check(Geodesic.triangulate(Arrays.asList(ray(0, 0, 0), null)) == null, "null ray rejected");
        check(Geodesic.triangulate(Arrays.asList(ray(-0.01, 0, 0), ray(0, -0.01, 90),
                ray(0.01, 0, 0), ray(0, 0.01, 90), ray(0.02, 0.02, 45))) == null,
                "majority-backward least-squares result rejected");

        // Oracle: independent great-circle distance/bearing, then intersect three short sight lines.
        Random random = new Random(20261025);
        for (int i = 0; i < 100; i++) {
            double lat = -70 + random.nextDouble() * 140;
            double lon = i % 2 == 0 ? 179.999 : -160 + random.nextDouble() * 320;
            Geodesic.Ray[] rays = new Geodesic.Ray[3];
            for (int j = 0; j < 3; j++) {
                double[] station = Geodesic.destination(lat, lon, j * 120, 100 + random.nextDouble() * 400);
                near(Waypoint.distance(lat, lon, station[0], station[1]),
                        Waypoint.distance(station[0], station[1], lat, lon), 1e-6, "symmetric distance");
                rays[j] = ray(station[0], station[1], Waypoint.bearing(station[0], station[1], lat, lon));
            }
            Geodesic.Intersection result = Geodesic.triangulate(Arrays.asList(rays));
            check(result != null, "three-station solution exists");
            check(Waypoint.distance(lat, lon, result.latitude, result.longitude) < 0.1,
                    "local solution agrees with spherical oracle within 10 cm");
            check(result.rmsMissM < 0.1, "local fit residual below 10 cm");
        }
        System.out.println("Geodesic checks passed: " + checks);
    }
}

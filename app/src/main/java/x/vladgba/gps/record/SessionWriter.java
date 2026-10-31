package x.vladgba.gps.record;

import android.location.GnssMeasurementsEvent;
import android.location.Location;
import android.os.Build;

import x.vladgba.gps.gnss.GnssMonitor;
import x.vladgba.gps.gnss.NmeaFix;
import x.vladgba.gps.gnss.SatelliteInfo;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TimeZone;

/**
 * Writes one recording session into its own directory, one file per enabled format. A failing
 * format is closed and reported; the others keep recording. Main thread only.
 */
final class SessionWriter implements GnssMonitor.Listener {

    private final File dir;
    private final Set<RecordingFormat> formats;
    private final SimpleDateFormat iso = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.ROOT);

    private Writer nmea;
    private Writer satellites;
    private Writer fixes;
    private Writer gpx;
    private Writer kml;
    private RinexWriter rinex;

    private long fixCount;
    private long epochCount;
    private long nmeaCount;
    private String error;
    private boolean closed;

    SessionWriter(File dir, Set<RecordingFormat> formats, long startUtcMs) {
        this.dir = dir;
        this.formats = formats;
        iso.setTimeZone(TimeZone.getTimeZone("UTC"));
        try {
            if (formats.contains(RecordingFormat.NMEA)) nmea = open("nmea.nmea");
            if (formats.contains(RecordingFormat.SATELLITES_CSV)) {
                satellites = open("satellites.csv");
                satellites.write("utc,constellation,svid,id,band,carrier_mhz,cn0_dbhz,baseband_cn0_dbhz,"
                        + "elevation_deg,azimuth_deg,used_in_fix,ephemeris,almanac\n");
            }
            if (formats.contains(RecordingFormat.FIXES_CSV)) {
                fixes = open("fixes.csv");
                fixes.write("utc,fix_time_utc,latitude,longitude,altitude_wgs84_m,altitude_msl_m,h_accuracy_m,"
                        + "v_accuracy_m,speed_mps,bearing_deg,satellites_used,satellites_in_view,hdop,pdop,mock\n");
            }
            if (formats.contains(RecordingFormat.GPX)) {
                gpx = open("track.gpx");
                gpx.write("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                        + "<gpx version=\"1.1\" creator=\"GPS Tools\" xmlns=\"http://www.topografix.com/GPX/1/1\">\n"
                        + "<trk><name>" + dir.getName() + "</name><trkseg>\n");
            }
            if (formats.contains(RecordingFormat.KML)) {
                kml = open("track.kml");
                kml.write("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                        + "<kml xmlns=\"http://www.opengis.net/kml/2.2\"><Document><name>" + dir.getName()
                        + "</name><Placemark><name>Track</name><LineString><tessellate>1</tessellate>"
                        + "<altitudeMode>clampToGround</altitudeMode><coordinates>\n");
            }
            if (formats.contains(RecordingFormat.RINEX)) rinex = new RinexWriter(dir, startUtcMs);
        } catch (IOException e) {
            error = e.getMessage();
        }
    }

    private Writer open(String name) throws IOException {
        return new BufferedWriter(new OutputStreamWriter(new FileOutputStream(new File(dir, name)), StandardCharsets.UTF_8));
    }

    @Override
    public void onGnssStateChanged(GnssMonitor monitor) {
    }

    @Override
    public void onNmeaMessage(GnssMonitor monitor, String sentence) {
        if (nmea == null) return;
        try {
            nmea.write(sentence);
            nmea.write("\r\n");
            nmeaCount++;
        } catch (IOException e) {
            nmea = fail(nmea, e);
        }
    }

    @Override
    public void onSatelliteStatus(GnssMonitor monitor, List<SatelliteInfo> list) {
        epochCount++;
        if (satellites == null) return;
        String now = iso.format(new Date());
        try {
            for (SatelliteInfo s : list) {
                String band = s.band();
                satellites.write(String.format(Locale.ROOT, "%s,%s,%d,%s,%s,%s,%.1f,%s,%.1f,%.1f,%d,%d,%d\n",
                        now, x.vladgba.gps.gnss.Constellations.name(s.constellation), s.svid, s.rinexId(),
                        band != null ? band : "", Float.isNaN(s.carrierHz) ? "" : String.format(Locale.ROOT, "%.3f", s.carrierHz / 1e6),
                        s.cn0, Float.isNaN(s.basebandCn0) ? "" : String.format(Locale.ROOT, "%.1f", s.basebandCn0),
                        s.elevation, s.azimuth, s.usedInFix ? 1 : 0, s.hasEphemeris ? 1 : 0, s.hasAlmanac ? 1 : 0));
            }
        } catch (IOException e) {
            satellites = fail(satellites, e);
        }
    }

    @Override
    public void onLocation(GnssMonitor monitor, Location loc) {
        fixCount++;
        String now = iso.format(new Date());
        String fixTime = iso.format(new Date(loc.getTime()));
        NmeaFix nmeaFix = monitor.getNmeaFix();
        boolean nmeaFresh = monitor.isNmeaFresh();
        double msl = Double.NaN;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && loc.hasMslAltitude()) {
            msl = loc.getMslAltitudeMeters();
        } else if (nmeaFresh) {
            msl = nmeaFix.getMslAltitude();
        }
        if (rinex != null && loc.hasAltitude()) rinex.setApproxPosition(loc.getLatitude(), loc.getLongitude(), loc.getAltitude());
        try {
            if (fixes != null) {
                String vAcc = "";
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && loc.hasVerticalAccuracy()) {
                    vAcc = String.format(Locale.ROOT, "%.1f", loc.getVerticalAccuracyMeters());
                }
                fixes.write(String.format(Locale.ROOT, "%s,%s,%.8f,%.8f,%s,%s,%s,%s,%s,%s,%d,%d,%s,%s,%d\n",
                        now, fixTime, loc.getLatitude(), loc.getLongitude(),
                        loc.hasAltitude() ? String.format(Locale.ROOT, "%.2f", loc.getAltitude()) : "",
                        Double.isNaN(msl) ? "" : String.format(Locale.ROOT, "%.2f", msl),
                        loc.hasAccuracy() ? String.format(Locale.ROOT, "%.1f", loc.getAccuracy()) : "",
                        vAcc,
                        loc.hasSpeed() ? String.format(Locale.ROOT, "%.2f", loc.getSpeed()) : "",
                        loc.hasBearing() ? String.format(Locale.ROOT, "%.1f", loc.getBearing()) : "",
                        monitor.getSatellitesUsed(), monitor.getSatellitesInView(),
                        nmeaFresh && !Double.isNaN(nmeaFix.getHdop()) ? String.format(Locale.ROOT, "%.2f", nmeaFix.getHdop()) : "",
                        nmeaFresh && !Double.isNaN(nmeaFix.getPdop()) ? String.format(Locale.ROOT, "%.2f", nmeaFix.getPdop()) : "",
                        isMock(loc) ? 1 : 0));
            }
        } catch (IOException e) {
            fixes = fail(fixes, e);
        }
        try {
            if (gpx != null) {
                StringBuilder pt = new StringBuilder(String.format(Locale.ROOT,
                        "<trkpt lat=\"%.8f\" lon=\"%.8f\">", loc.getLatitude(), loc.getLongitude()));
                double ele = !Double.isNaN(msl) ? msl : loc.hasAltitude() ? loc.getAltitude() : Double.NaN;
                if (!Double.isNaN(ele)) pt.append(String.format(Locale.ROOT, "<ele>%.2f</ele>", ele));
                pt.append("<time>").append(fixTime).append("</time>");
                pt.append("<sat>").append(monitor.getSatellitesUsed()).append("</sat>");
                if (nmeaFresh && !Double.isNaN(nmeaFix.getHdop())) {
                    pt.append(String.format(Locale.ROOT, "<hdop>%.2f</hdop>", nmeaFix.getHdop()));
                }
                pt.append("</trkpt>\n");
                gpx.write(pt.toString());
            }
        } catch (IOException e) {
            gpx = fail(gpx, e);
        }
        try {
            if (kml != null) {
                kml.write(String.format(Locale.ROOT, "%.8f,%.8f,%.1f\n", loc.getLongitude(), loc.getLatitude(),
                        loc.hasAltitude() ? loc.getAltitude() : 0.0));
            }
        } catch (IOException e) {
            kml = fail(kml, e);
        }
        flushAll();
    }

    @Override
    public void onMeasurements(GnssMonitor monitor, GnssMeasurementsEvent event) {
        if (rinex == null) return;
        try {
            rinex.addEpoch(event);
        } catch (IOException e) {
            error = e.getMessage();
            rinex = null;
        }
    }

    private void flushAll() {
        for (Writer w : new Writer[]{nmea, satellites, fixes, gpx, kml}) {
            if (w == null) continue;
            try {
                w.flush();
            } catch (IOException e) {
                error = e.getMessage();
            }
        }
    }

    /** Finishes every file (closing tags, RINEX header). */
    void close() {
        if (closed) return;
        closed = true;
        try {
            if (gpx != null) gpx.write("</trkseg></trk>\n</gpx>\n");
            if (kml != null) kml.write("</coordinates></LineString></Placemark></Document></kml>\n");
        } catch (IOException e) {
            error = e.getMessage();
        }
        for (Writer w : new Writer[]{nmea, satellites, fixes, gpx, kml}) {
            if (w == null) continue;
            try {
                w.close();
            } catch (IOException e) {
                error = e.getMessage();
            }
        }
        if (rinex != null) {
            try {
                rinex.finish();
            } catch (IOException e) {
                error = e.getMessage();
            }
        }
    }

    private Writer fail(Writer w, IOException e) {
        error = e.getMessage();
        try {
            w.close();
        } catch (IOException ignored) {
            // already failing
        }
        return null;
    }

    @SuppressWarnings("deprecation")
    private static boolean isMock(Location loc) {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ? loc.isMock() : loc.isFromMockProvider();
    }

    // ---- Stats for the UI ---------------------------------------------------------------------

    File getDir() {
        return dir;
    }

    long getFixCount() {
        return fixCount;
    }

    long getEpochCount() {
        return epochCount;
    }

    long getNmeaCount() {
        return nmeaCount;
    }

    int getRinexEpochs() {
        return rinex != null ? rinex.getEpochs() : 0;
    }

    boolean isRecordingRinex() {
        return rinex != null;
    }

    String getError() {
        return error;
    }
}

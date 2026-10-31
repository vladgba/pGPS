package x.vladgba.gps.gnss;

import android.annotation.SuppressLint;
import android.location.GnssStatus;

import java.util.Locale;

/**
 * Static knowledge about GNSS constellations: names, colors, RINEX ids and signal bands.
 * CONSTELLATION_* values are compile-time constants, so referencing ones newer than minSdk
 * (IRNSS is API 29) is safe: older devices simply never report them.
 */
@SuppressLint("InlinedApi")
public final class Constellations {

    /** Display order used everywhere in the app. */
    public static final int[] ORDER = {
            GnssStatus.CONSTELLATION_GPS,
            GnssStatus.CONSTELLATION_GLONASS,
            GnssStatus.CONSTELLATION_GALILEO,
            GnssStatus.CONSTELLATION_BEIDOU,
            GnssStatus.CONSTELLATION_QZSS,
            GnssStatus.CONSTELLATION_IRNSS,
            GnssStatus.CONSTELLATION_SBAS,
            GnssStatus.CONSTELLATION_UNKNOWN,
    };

    private static final double MHZ = 1e6;
    /** Carrier frequency match tolerance; covers receiver rounding of the reported value. */
    private static final double BAND_TOLERANCE_MHZ = 0.6;

    private Constellations() {
    }

    public static String name(int constellation) {
        switch (constellation) {
            case GnssStatus.CONSTELLATION_GPS: return "GPS";
            case GnssStatus.CONSTELLATION_GLONASS: return "GLONASS";
            case GnssStatus.CONSTELLATION_GALILEO: return "Galileo";
            case GnssStatus.CONSTELLATION_BEIDOU: return "BeiDou";
            case GnssStatus.CONSTELLATION_QZSS: return "QZSS";
            case GnssStatus.CONSTELLATION_IRNSS: return "NavIC";
            case GnssStatus.CONSTELLATION_SBAS: return "SBAS";
            default: return "Unknown";
        }
    }

    /** Operator / country, shown next to the constellation name. */
    public static String operator(int constellation) {
        switch (constellation) {
            case GnssStatus.CONSTELLATION_GPS: return "USA";
            case GnssStatus.CONSTELLATION_GLONASS: return "Russia";
            case GnssStatus.CONSTELLATION_GALILEO: return "EU";
            case GnssStatus.CONSTELLATION_BEIDOU: return "China";
            case GnssStatus.CONSTELLATION_QZSS: return "Japan";
            case GnssStatus.CONSTELLATION_IRNSS: return "India";
            case GnssStatus.CONSTELLATION_SBAS: return "Augmentation";
            default: return "";
        }
    }

    public static int color(int constellation) {
        switch (constellation) {
            case GnssStatus.CONSTELLATION_GPS: return 0xFF42A5F5;
            case GnssStatus.CONSTELLATION_GLONASS: return 0xFFEF5350;
            case GnssStatus.CONSTELLATION_GALILEO: return 0xFFFFCA28;
            case GnssStatus.CONSTELLATION_BEIDOU: return 0xFFAB47BC;
            case GnssStatus.CONSTELLATION_QZSS: return 0xFF26A69A;
            case GnssStatus.CONSTELLATION_IRNSS: return 0xFFFF7043;
            case GnssStatus.CONSTELLATION_SBAS: return 0xFF9CCC65;
            default: return 0xFFBDBDBD;
        }
    }

    public static int rank(int constellation) {
        for (int i = 0; i < ORDER.length; i++) {
            if (ORDER[i] == constellation) return i;
        }
        return ORDER.length;
    }

    /**
     * RINEX-style satellite id (G05, R07, E11, C23, J02, I03, S31). GLONASS satellites whose
     * slot is unknown are reported by Android as 93..106 (frequency channel + 100).
     */
    public static String rinexId(int constellation, int svid) {
        switch (constellation) {
            case GnssStatus.CONSTELLATION_GPS: return id('G', svid);
            case GnssStatus.CONSTELLATION_GLONASS:
                return svid >= 93 && svid <= 106 ? "R?" : id('R', svid);
            case GnssStatus.CONSTELLATION_GALILEO: return id('E', svid);
            case GnssStatus.CONSTELLATION_BEIDOU: return id('C', svid);
            case GnssStatus.CONSTELLATION_QZSS:
                return svid >= 193 && svid <= 202 ? id('J', svid - 192) : id('J', svid);
            case GnssStatus.CONSTELLATION_IRNSS: return id('I', svid);
            case GnssStatus.CONSTELLATION_SBAS:
                return svid >= 120 && svid <= 158 ? id('S', svid - 100) : id('S', svid);
            default: return "?" + svid;
        }
    }

    /** Human readable identifier: "PRN 5", "Slot 7", "FCN -3". */
    public static String idLabel(int constellation, int svid) {
        if (constellation == GnssStatus.CONSTELLATION_GLONASS) {
            if (svid >= 93 && svid <= 106) {
                return String.format(Locale.ROOT, "FCN %+d", svid - 100);
            }
            return "Slot " + svid;
        }
        return "PRN " + svid;
    }

    /**
     * SBAS service by PRN. Assignments follow the published SBAS PRN table; operators occasionally
     * move PRNs, so an unmatched PRN is simply reported without a service name.
     */
    public static String sbasService(int prn) {
        switch (prn) {
            case 131: case 133: case 135: case 138: return "WAAS";
            case 120: case 121: case 123: case 124: case 126: case 136: return "EGNOS";
            case 129: case 137: return "MSAS";
            case 127: case 128: case 132: return "GAGAN";
            case 125: case 140: case 141: return "SDCM";
            case 130: case 143: case 144: return "BDSBAS";
            case 134: return "KASS";
            case 122: return "SouthPAN";
            default: return null;
        }
    }

    /** Signal band from the carrier frequency ("L1", "E5a", "B1I"…), or null when unknown. */
    public static String band(int constellation, float carrierHz) {
        if (Float.isNaN(carrierHz) || carrierHz <= 0) return null;
        double mhz = carrierHz / MHZ;
        switch (constellation) {
            case GnssStatus.CONSTELLATION_GPS:
            case GnssStatus.CONSTELLATION_SBAS:
            case GnssStatus.CONSTELLATION_QZSS:
                if (near(mhz, 1575.42)) return "L1";
                if (near(mhz, 1227.60)) return "L2";
                if (near(mhz, 1176.45)) return "L5";
                if (near(mhz, 1278.75)) return "L6";
                break;
            case GnssStatus.CONSTELLATION_GALILEO:
                if (near(mhz, 1575.42)) return "E1";
                if (near(mhz, 1176.45)) return "E5a";
                if (near(mhz, 1207.14)) return "E5b";
                if (near(mhz, 1278.75)) return "E6";
                break;
            case GnssStatus.CONSTELLATION_BEIDOU:
                if (near(mhz, 1561.098)) return "B1I";
                if (near(mhz, 1575.42)) return "B1C";
                if (near(mhz, 1176.45)) return "B2a";
                if (near(mhz, 1207.14)) return "B2I";
                if (near(mhz, 1268.52)) return "B3I";
                break;
            case GnssStatus.CONSTELLATION_GLONASS:
                // FDMA: each satellite sits on its own channel inside these ranges.
                if (mhz >= 1597.5 && mhz <= 1606.5) return "G1";
                if (mhz >= 1241.5 && mhz <= 1249.5) return "G2";
                if (near(mhz, 1202.025)) return "G3";
                break;
            case GnssStatus.CONSTELLATION_IRNSS:
                if (near(mhz, 1176.45)) return "L5";
                if (near(mhz, 2492.028)) return "S";
                break;
            default:
                break;
        }
        return String.format(Locale.ROOT, "%.2f MHz", mhz);
    }

    private static boolean near(double mhz, double target) {
        return Math.abs(mhz - target) <= BAND_TOLERANCE_MHZ;
    }

    private static String id(char prefix, int number) {
        return String.format(Locale.ROOT, "%c%02d", prefix, number);
    }
}

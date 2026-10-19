package com.vladgba.gpstools.gnss;

import android.location.GnssStatus;
import android.os.Build;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Immutable copy of one entry of {@link GnssStatus}. Note that a dual-frequency receiver
 * reports the same satellite once per signal (L1 and L5…), so several entries can share a
 * {@link #key()}.
 */
public final class SatelliteInfo {

    public final int constellation;
    public final int svid;
    /** Carrier-to-noise density at the antenna, dB-Hz. */
    public final float cn0;
    public final float elevation;
    public final float azimuth;
    /** Carrier frequency in Hz, NaN when the device does not report it (API 26+). */
    public final float carrierHz;
    /** Baseband C/N0 in dB-Hz, NaN when not reported (API 30+). */
    public final float basebandCn0;
    public final boolean usedInFix;
    public final boolean hasAlmanac;
    public final boolean hasEphemeris;

    public static final Comparator<SatelliteInfo> BY_SYSTEM = (a, b) -> {
        int c = Integer.compare(Constellations.rank(a.constellation), Constellations.rank(b.constellation));
        if (c != 0) return c;
        c = Integer.compare(a.svid, b.svid);
        if (c != 0) return c;
        return Float.compare(a.carrierHz, b.carrierHz);
    };

    public static final Comparator<SatelliteInfo> BY_SIGNAL =
            (a, b) -> {
                int c = Float.compare(b.cn0, a.cn0);
                return c != 0 ? c : BY_SYSTEM.compare(a, b);
            };

    public static final Comparator<SatelliteInfo> BY_ELEVATION =
            (a, b) -> {
                int c = Float.compare(b.elevation, a.elevation);
                return c != 0 ? c : BY_SYSTEM.compare(a, b);
            };

    public SatelliteInfo(int constellation, int svid, float cn0, float elevation, float azimuth,
                         float carrierHz, float basebandCn0,
                         boolean usedInFix, boolean hasAlmanac, boolean hasEphemeris) {
        this.constellation = constellation;
        this.svid = svid;
        this.cn0 = cn0;
        this.elevation = elevation;
        this.azimuth = azimuth;
        this.carrierHz = carrierHz;
        this.basebandCn0 = basebandCn0;
        this.usedInFix = usedInFix;
        this.hasAlmanac = hasAlmanac;
        this.hasEphemeris = hasEphemeris;
    }

    public static List<SatelliteInfo> fromStatus(GnssStatus status) {
        int count = status.getSatelliteCount();
        List<SatelliteInfo> list = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            float carrier = Float.NaN;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && status.hasCarrierFrequencyHz(i)) {
                carrier = status.getCarrierFrequencyHz(i);
            }
            float baseband = Float.NaN;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && status.hasBasebandCn0DbHz(i)) {
                baseband = status.getBasebandCn0DbHz(i);
            }
            list.add(new SatelliteInfo(
                    status.getConstellationType(i),
                    status.getSvid(i),
                    status.getCn0DbHz(i),
                    status.getElevationDegrees(i),
                    status.getAzimuthDegrees(i),
                    carrier,
                    baseband,
                    status.usedInFix(i),
                    status.hasAlmanacData(i),
                    status.hasEphemerisData(i)));
        }
        Collections.sort(list, BY_SYSTEM);
        return Collections.unmodifiableList(list);
    }

    /** Identifies the satellite itself, independent of the signal. */
    public long key() {
        return ((long) constellation << 32) | (svid & 0xFFFFFFFFL);
    }

    /** Receivers report 0/0 for satellites they track but have no orbit data for yet. */
    public boolean hasSkyPosition() {
        return !(elevation == 0f && azimuth == 0f);
    }

    public String rinexId() {
        return Constellations.rinexId(constellation, svid);
    }

    /** Band label ("L1", "E5a"…) or null when the carrier frequency is unknown. */
    public String band() {
        return Constellations.band(constellation, carrierHz);
    }
}

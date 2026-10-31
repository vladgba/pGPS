package x.vladgba.gps.record;

/** Files a recording session can produce. */
public enum RecordingFormat {
    NMEA,
    SATELLITES_CSV,
    FIXES_CSV,
    GPX,
    KML,
    /** RINEX 3.04 observations; needs raw measurement support. */
    RINEX
}

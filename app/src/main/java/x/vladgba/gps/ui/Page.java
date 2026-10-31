package x.vladgba.gps.ui;

import android.view.View;

import x.vladgba.gps.gnss.GnssMonitor;

/** One tab (or Tools sub-page) of the main screen. Only the visible page receives updates. */
public interface Page {

    View getView();

    /** Called when the page becomes visible and on every GNSS state change while visible. */
    void update(GnssMonitor monitor);

    /** Called for each NMEA sentence while the page is visible. */
    default void onNmeaMessage(GnssMonitor monitor, String sentence) {
    }

    /** Whether system Back should be handled by the page (e.g. to leave a sub-page). */
    default boolean canGoBack() {
        return false;
    }

    /** Handles system Back; only called when {@link #canGoBack()} is true. */
    default void goBack() {
    }

    /** Result of a runtime permission request the page started (forwarded by the activity). */
    default void onPermissionResult(int requestCode, boolean granted) {
    }

    /** The page was removed from the screen. */
    default void onHidden() {
    }
}

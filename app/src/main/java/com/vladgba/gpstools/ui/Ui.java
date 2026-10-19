package com.vladgba.gpstools.ui;

import android.content.Context;
import android.util.TypedValue;

/** Small view helpers shared by the pages. */
final class Ui {

    private Ui() {
    }

    static int dp(Context context, float value) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                context.getResources().getDisplayMetrics()));
    }

    static float sp(Context context, float value) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, value,
                context.getResources().getDisplayMetrics());
    }

    /** Signal-strength color: red below 20 dB-Hz, orange below 30, green above. */
    static int signalColor(float cn0) {
        if (cn0 < 20f) return 0xFFE53935;
        if (cn0 < 30f) return 0xFFFB8C00;
        return 0xFF43A047;
    }
}

package com.vladgba.gpstools.gnss;

import java.util.ArrayDeque;

/** Fixed time-window series of (elapsed ms, value) samples, oldest first. */
public final class TimeSeries {

    private final long windowMs;
    private final ArrayDeque<double[]> samples = new ArrayDeque<>();

    public TimeSeries(long windowMs) {
        this.windowMs = windowMs;
    }

    public void add(long elapsedMs, double value) {
        samples.addLast(new double[]{elapsedMs, value});
        trim(elapsedMs);
    }

    public void trim(long nowElapsedMs) {
        while (!samples.isEmpty() && nowElapsedMs - samples.peekFirst()[0] > windowMs) {
            samples.removeFirst();
        }
    }

    public void clear() {
        samples.clear();
    }

    public long getWindowMs() {
        return windowMs;
    }

    /** Each element is {elapsedMs, value}; do not modify. */
    public Iterable<double[]> samples() {
        return samples;
    }

    public boolean isEmpty() {
        return samples.isEmpty();
    }
}

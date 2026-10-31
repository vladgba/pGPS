package x.vladgba.gps.gnss;

/**
 * Fixed-capacity (elapsed ms, value) ring buffer. Primitive arrays instead of objects so the
 * 30-minute histories of dozens of satellites stay cheap. Oldest sample at index 0.
 */
public final class RingSeries {

    private final long[] times;
    private final float[] values;
    private int start;
    private int size;

    public RingSeries(int capacity) {
        times = new long[capacity];
        values = new float[capacity];
    }

    public void add(long elapsedMs, float value) {
        int index = (start + size) % times.length;
        if (size == times.length) {
            start = (start + 1) % times.length;
        } else {
            size++;
        }
        times[index] = elapsedMs;
        values[index] = value;
    }

    public int size() {
        return size;
    }

    public long time(int i) {
        return times[(start + i) % times.length];
    }

    public float value(int i) {
        return values[(start + i) % times.length];
    }

    public long lastTime() {
        return size == 0 ? Long.MIN_VALUE : time(size - 1);
    }

    public void clear() {
        start = 0;
        size = 0;
    }
}

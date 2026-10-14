package teammapsync.net;

import java.util.Arrays;

/** Sequence numbers waiting to be acknowledged, sent in batches. */
public final class SeqList {

    private int[] values = new int[64];
    private int size;

    public void add(int seq) {
        if (size == values.length) {
            values = Arrays.copyOf(values, size * 2);
        }
        values[size++] = seq;
    }

    public boolean isEmpty() {
        return size == 0;
    }

    public void clear() {
        size = 0;
    }

    /** Removes and returns up to {@code max} entries. */
    public int[] take(int max) {
        int n = Math.min(max, size);
        int[] out = Arrays.copyOf(values, n);
        System.arraycopy(values, n, values, 0, size - n);
        size -= n;
        return out;
    }
}

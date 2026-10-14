package teammapsync.net;

import java.util.BitSet;

/**
 * Remembers which sequence numbers of one session were already received, so a retransmission whose original did
 * arrive (only the acknowledgement was lost) is acknowledged again but not applied twice.
 *
 * <p>An honest sender's numbers only run ahead by what it has in flight, so a jump beyond the window is refused:
 * that keeps a crafted packet from forcing a long clearing pass or an overflow.
 */
public final class SeqWindow {

    /** Senders keep their oldest unacknowledged number within half of this (see {@link ReliableChannel}). */
    static final int SIZE = 1 << 16;
    private static final int MASK = SIZE - 1;

    private final BitSet seen = new BitSet(SIZE);
    private int highest;

    /** @return false for numbers that are implausible, too far ahead, or already seen */
    public boolean isAcceptable(int seq) {
        return seq > 0 && (long) seq - highest < SIZE;
    }

    /** @return true the first time {@code seq} is seen; numbers older than the window count as seen */
    public boolean accept(int seq) {
        if (!isAcceptable(seq) || (long) seq <= (long) highest - SIZE) {
            return false;
        }
        if (seq > highest) {
            for (int i = 1, steps = seq - highest; i <= steps; i++) {
                seen.clear((highest + i) & MASK);
            }
            highest = seq;
        }
        if (seen.get(seq & MASK)) {
            return false;
        }
        seen.set(seq & MASK);
        return true;
    }
}

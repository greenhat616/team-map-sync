package teammapsync.net;

import java.util.HashMap;
import java.util.function.Consumer;

/**
 * Hands ordered packets (live tiles, marker changes) on in the order they were sent. They arrive reliably but not
 * in order; applying a live tile update or a marker change out of order could undo a newer one, so a packet waits
 * here until every earlier one arrived.
 */
public final class OrderedBuffer<T> {

    /** How far ahead of the next expected packet one may be; a sender never has more than that in flight. */
    public static final int MAX_AHEAD = 4096;

    private final HashMap<Integer, T> waiting = new HashMap<>();
    private int next = 1;

    /** @return false if {@code ordinal} cannot be from an honest sender; the packet should not be acknowledged */
    public boolean isAcceptable(int ordinal) {
        return ordinal > 0 && (long) ordinal - next < MAX_AHEAD;
    }

    /** Hands on everything still waiting, in order, skipping the gaps; used when the connection ends. */
    public void flushWaiting(Consumer<T> deliver) {
        waiting.keySet().stream().sorted().forEach(ordinal -> deliver.accept(waiting.get(ordinal)));
        waiting.clear();
    }

    public void receive(int ordinal, T item, Consumer<T> deliver) {
        if (ordinal < next || !isAcceptable(ordinal)) {
            return;
        }
        waiting.put(ordinal, item);
        T ready;
        while ((ready = waiting.remove(next)) != null) {
            next++;
            deliver.accept(ready);
        }
    }
}

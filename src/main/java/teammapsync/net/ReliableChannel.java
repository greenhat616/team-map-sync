package teammapsync.net;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import necesse.engine.network.Packet;

/**
 * Acknowledged, rate limited delivery for one direction of one sync session.
 *
 * <p>Vanilla's transport is unreliable: packets are cut into 1 KB datagrams (Steam "unreliable" messages or plain
 * UDP), a packet missing one of them is dropped, and nothing is resent. Vanilla features recover on their own
 * (region requests are repeated after two seconds), so this mod has to as well: every packet sent through here
 * carries a sequence number, the receiver acknowledges it, and it is resent with backoff until acknowledged.
 *
 * <p>Ordered packets (live tiles, markers) also carry a gapless ordinal, so the receiver can apply them in sending
 * order with an {@link OrderedBuffer}; they go before bulk packets. Bulk packets (stored and backlog map data)
 * only fill undiscovered tiles, so their order does not matter.
 *
 * <p>Sequence numbers and ordinals are assigned when a packet is first sent, not when it is queued. So sequence
 * numbers grow in sending order, which lets the sender keep the oldest unacknowledged one inside the receiver's
 * {@link SeqWindow}; and ordered packets not sent yet can be dropped ({@link #dropOrdered}) without leaving a gap
 * the receiver would wait on forever.
 *
 * <p>Bytes in flight are capped, so a congested connection slows this traffic down through late acknowledgements
 * instead of piling more onto it; bulk data may only use part of the cap, so ordered packets are never stuck
 * behind it. The byte allowance carries its overdraft into later ticks, so a large packet cannot exceed the
 * configured average rate.
 *
 * <p>{@link #enqueue} may be called from any thread; everything else runs on the owning game or server thread,
 * which is also where {@code onAcked} callbacks run.
 */
public final class ReliableChannel {

    private static final long FIRST_TIMEOUT_MS = 2000;
    private static final long MAX_TIMEOUT_MS = 16000;
    /** Half the receiver's sequence window: new packets wait while the oldest unacknowledged one is this far behind. */
    private static final int MAX_SEQ_SPREAD = SeqWindow.SIZE / 2;

    public interface Builder {
        /** @param ordinal position in the ordered stream, or 0 for a bulk packet */
        Packet build(int seq, int ordinal);
    }

    private final int bytesPerTick;
    private final int maxInFlightBytes;
    private volatile int epoch = -1;
    private int sequence;
    private int ordinal;
    private final ConcurrentLinkedQueue<Entry> ordered = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<Entry> bulk = new ConcurrentLinkedQueue<>();
    private final AtomicInteger bulkQueued = new AtomicInteger();
    private final AtomicInteger bulkQueuedBytes = new AtomicInteger();
    private final AtomicInteger orderedQueuedBytes = new AtomicInteger();
    private final AtomicInteger droppableQueuedBytes = new AtomicInteger();
    private final LinkedHashMap<Integer, Entry> inFlight = new LinkedHashMap<>();
    private int inFlightBytes;
    private int allowance;

    public ReliableChannel(int bytesPerTick, int maxInFlightBytes) {
        this.bytesPerTick = bytesPerTick;
        this.maxInFlightBytes = maxInFlightBytes;
    }

    /** Starts a new session: everything queued or unacknowledged for the previous one is dropped. Owning thread. */
    public synchronized void reset(int newEpoch) {
        epoch = newEpoch;
        sequence = 0;
        ordinal = 0;
        ordered.clear();
        bulk.clear();
        bulkQueued.set(0);
        bulkQueuedBytes.set(0);
        orderedQueuedBytes.set(0);
        droppableQueuedBytes.set(0);
        inFlight.clear();
        inFlightBytes = 0;
        allowance = 0;
    }

    /**
     * @param tag kept with the packet and returned by {@link #dropOrdered} if it is dropped; null for packets that
     *            must never be dropped (markers)
     * @return false if the session changed since {@code forEpoch} was read; nothing is queued then
     */
    public synchronized boolean enqueue(int forEpoch, boolean isOrdered, Builder build, int size, Runnable onAcked, Object tag) {
        if (forEpoch != epoch) {
            return false;
        }
        Entry entry = new Entry(forEpoch, build, size, onAcked, tag);
        if (isOrdered) {
            orderedQueuedBytes.addAndGet(entry.size);
            if (tag != null) {
                droppableQueuedBytes.addAndGet(entry.size);
            }
            ordered.add(entry);
        } else {
            bulkQueued.incrementAndGet();
            bulkQueuedBytes.addAndGet(entry.size);
            bulk.add(entry);
        }
        return true;
    }

    public boolean enqueue(int forEpoch, boolean isOrdered, Builder build, int size, Runnable onAcked) {
        return enqueue(forEpoch, isOrdered, build, size, onAcked, null);
    }

    /** Bulk packets waiting for their first send; producers refill below a low-water mark. */
    public int bulkQueued() {
        return bulkQueued.get();
    }

    public int bulkQueuedBytes() {
        return bulkQueuedBytes.get();
    }

    /** Bytes of ordered packets waiting for their first send; grows when the receiver cannot keep up. */
    public int orderedQueuedBytes() {
        return orderedQueuedBytes.get();
    }

    /** Bytes of tagged (droppable) ordered packets waiting for their first send. */
    public int droppableQueuedBytes() {
        return droppableQueuedBytes.get();
    }

    /**
     * Drops every tagged ordered packet not sent yet (untagged ones stay) and returns their tags. Owning thread.
     * Safe because ordinals are only assigned on sending.
     */
    public List<Object> dropOrdered() {
        ArrayList<Object> tags = new ArrayList<>();
        Iterator<Entry> it = ordered.iterator();
        while (it.hasNext()) {
            Entry entry = it.next();
            if (entry.tag != null) {
                it.remove();
                orderedQueuedBytes.addAndGet(-entry.size);
                droppableQueuedBytes.addAndGet(-entry.size);
                tags.add(entry.tag);
            }
        }
        return tags;
    }

    public void ack(int seq) {
        Entry entry = inFlight.remove(seq);
        if (entry != null) {
            inFlightBytes -= entry.size;
            if (entry.onAcked != null) {
                entry.onAcked.run();
            }
        }
    }

    /**
     * Sends due retransmissions, then ordered packets, then bulk packets.
     *
     * @param throttled true while the receiver's vanilla region loading is busy; bulk then backs off further
     * @param budget    bytes this channel may send this tick under a shared (server-wide) budget
     * @return bytes sent
     */
    public int tick(long now, boolean throttled, int budget, Consumer<Packet> out) {
        int current = epoch;
        allowance = Math.min(bytesPerTick, allowance + bytesPerTick);
        int sent = 0;

        // Collected first: sending may lead to an acknowledgement (and removal) before this loop ends.
        ArrayList<Entry> due = new ArrayList<>();
        int dueBytes = 0;
        for (Entry entry : inFlight.values()) {
            if (allowance - dueBytes <= 0 || sent + dueBytes >= budget) {
                break;
            }
            if (now >= entry.deadline) {
                due.add(entry);
                dueBytes += entry.size;
            }
        }
        for (Entry entry : due) {
            entry.timeout = Math.min(MAX_TIMEOUT_MS, entry.timeout * 2);
            entry.deadline = now + entry.timeout;
            allowance -= entry.size;
            sent += entry.size;
            out.accept(entry.packet);
        }
        Entry entry;
        while (allowance > 0 && sent < budget && inFlightBytes < maxInFlightBytes && withinSeqSpread() && (entry = ordered.poll()) != null) {
            orderedQueuedBytes.addAndGet(-entry.size);
            if (entry.tag != null) {
                droppableQueuedBytes.addAndGet(-entry.size);
            }
            if (entry.epoch == current) {
                entry.packet = entry.build.build(++sequence, ++ordinal);
                sent += transmit(entry, now, out);
            }
        }

        int bulkCap = throttled ? maxInFlightBytes / 8 : maxInFlightBytes / 2;
        while (allowance > 0 && sent < budget && inFlightBytes < bulkCap && withinSeqSpread() && (entry = bulk.poll()) != null) {
            bulkQueued.decrementAndGet();
            bulkQueuedBytes.addAndGet(-entry.size);
            if (entry.epoch == current) {
                entry.packet = entry.build.build(++sequence, 0);
                sent += transmit(entry, now, out);
            }
        }
        return sent;
    }

    /** New packets wait while the oldest unacknowledged one would fall out of the receiver's window. */
    private boolean withinSeqSpread() {
        for (Map.Entry<Integer, Entry> oldest : inFlight.entrySet()) {
            return sequence + 1 - oldest.getKey() < MAX_SEQ_SPREAD;
        }
        return true;
    }

    private int transmit(Entry entry, long now, Consumer<Packet> out) {
        // Registered before sending, in case the acknowledgement comes back within the send call.
        entry.timeout = FIRST_TIMEOUT_MS;
        entry.deadline = now + FIRST_TIMEOUT_MS;
        inFlight.put(sequence, entry);
        inFlightBytes += entry.size;
        allowance -= entry.size;
        out.accept(entry.packet);
        return entry.size;
    }

    private static final class Entry {
        final int epoch;
        final Builder build;
        final int size;
        final Runnable onAcked;
        final Object tag;
        Packet packet;
        long timeout;
        long deadline;

        Entry(int epoch, Builder build, int size, Runnable onAcked, Object tag) {
            this.epoch = epoch;
            this.build = build;
            this.size = Math.max(1, size);
            this.onAcked = onAcked;
            this.tag = tag;
        }
    }
}

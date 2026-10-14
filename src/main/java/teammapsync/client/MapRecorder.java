package teammapsync.client;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.IdentityHashMap;
import necesse.level.maps.mapData.ClientDiscoveredMap;
import necesse.level.maps.mapData.ClientDiscoveredMapRegion;

/**
 * Collects the map tiles this player's own discovery changes, as they are written.
 *
 * <p>Hooked into {@code ClientDiscoveredMapRegion.setRGBA}, but only while the real (saved) map runs
 * {@code tickDiscovery}/{@code tickUpdate}: that excludes the debug cheat map and the tiles this mod applies from
 * teammates, so nothing echoes back. Each changed tile costs one append to a primitive array; deduplication and
 * sorting happen once per flush. Game thread only.
 */
public final class MapRecorder {

    /** Read by the inlined setRGBA advice, so it must stay a public static field. */
    public static boolean recording;

    static boolean enabled;
    private static int depth;
    private static String activeLevel;
    private static final IdentityHashMap<ClientDiscoveredMapRegion, Buffer> buffers = new IdentityHashMap<>();
    private static ClientDiscoveredMapRegion lastRegion;
    private static Buffer lastBuffer;
    private static final BitSet seen = new BitSet(256 * 256);

    private MapRecorder() {
    }

    public static void begin(ClientDiscoveredMap map) {
        if (enabled && map.shouldSave) {
            depth++;
            activeLevel = map.level.getIdentifier().stringID;
            recording = true;
        }
    }

    public static void end() {
        if (depth > 0 && --depth == 0) {
            recording = false;
            lastRegion = null;
            lastBuffer = null;
        }
    }

    public static void record(ClientDiscoveredMapRegion region, int tileX, int tileY, int argb) {
        Buffer buffer = region == lastRegion ? lastBuffer : null;
        if (buffer == null) {
            buffer = buffers.get(region);
            if (buffer == null) {
                buffer = new Buffer(activeLevel, region.mapRegionX, region.mapRegionY);
                buffers.put(region, buffer);
            }
            lastRegion = region;
            lastBuffer = buffer;
        }
        buffer.add(tileX | tileY << 8, argb & 0xFFFFFF);
    }

    interface PendingTile {
        void accept(String level, int regionX, int regionY, int index);
    }

    /** Every tile recorded since the last {@link #drain}, without draining. */
    static void forEachPending(PendingTile consumer) {
        for (Buffer buffer : buffers.values()) {
            for (int i = 0; i < buffer.size; i++) {
                consumer.accept(buffer.level, buffer.regionX, buffer.regionY, (int) (buffer.entries[i] >>> 32));
            }
        }
    }

    static void clear() {
        buffers.clear();
        lastRegion = null;
        lastBuffer = null;
    }

    /** Takes everything recorded since the last call, one entry per region, deduplicated and sorted. */
    static ArrayList<Delta> drain() {
        ArrayList<Delta> out = new ArrayList<>(buffers.size());
        for (Buffer buffer : buffers.values()) {
            if (buffer.size > 0) {
                out.add(buffer.drain());
            }
        }
        buffers.clear();
        lastRegion = null;
        lastBuffer = null;
        return out;
    }

    /** Tiles of one region, ready to encode. */
    static final class Delta {
        final String level;
        final int regionX;
        final int regionY;
        final int[] indices;
        final int[] rgb;

        Delta(String level, int regionX, int regionY, int[] indices, int[] rgb) {
            this.level = level;
            this.regionX = regionX;
            this.regionY = regionY;
            this.indices = indices;
            this.rgb = rgb;
        }
    }

    private static final class Buffer {
        final String level;
        final int regionX;
        final int regionY;
        long[] entries = new long[256];
        int size;

        Buffer(String level, int regionX, int regionY) {
            this.level = level;
            this.regionX = regionX;
            this.regionY = regionY;
        }

        void add(int index, int rgb) {
            if (size == entries.length) {
                if (size >= 256 * 256) {
                    compact();
                }
                if (size == entries.length) {
                    entries = Arrays.copyOf(entries, size * 2);
                }
            }
            entries[size++] = (long) index << 32 | rgb & 0xFFFFFFFFL;
        }

        /** Keeps the newest colour per tile and sorts by tile index (the index sits in the high bits). */
        private void compact() {
            int kept = 0;
            long[] out = new long[size];
            for (int i = size - 1; i >= 0; i--) {
                int index = (int) (entries[i] >>> 32);
                if (!seen.get(index)) {
                    seen.set(index);
                    out[kept++] = entries[i];
                }
            }
            seen.clear();
            Arrays.sort(out, 0, kept);
            entries = out;
            size = kept;
        }

        Delta drain() {
            compact();
            int[] indices = new int[size];
            int[] rgb = new int[size];
            for (int i = 0; i < size; i++) {
                indices[i] = (int) (entries[i] >>> 32);
                rgb[i] = (int) entries[i];
            }
            return new Delta(level, regionX, regionY, indices, rgb);
        }
    }
}

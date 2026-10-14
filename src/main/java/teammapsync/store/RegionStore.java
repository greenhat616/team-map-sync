package teammapsync.store;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.DataFormatException;
import necesse.engine.GameLog;
import teammapsync.TeamMarker;
import teammapsync.codec.TileCodec;

/**
 * Map tiles kept outside vanilla's files, one file per bucket and map region. The server keeps one bucket per team
 * (the team's merged map); the client keeps an inbox of teammates' tiles for levels it is not on. Only ever used
 * from one worker thread.
 *
 * <p>Recently used regions stay in a small LRU cache, so a player walking around costs one decode per region, not
 * one per update. A region whose file could not be written stays dirty in memory and is retried.
 */
public final class RegionStore {

    /** Set on every stored tile, so pure black (rgb 0) is still distinguishable from undiscovered. */
    public static final int PRESENT = 0x1000000;
    /** Set on tiles last written by live data, which the client inbox replays as overwriting. */
    public static final int LIVE = 0x2000000;

    private static final int FILE_MAGIC = 0x544D5352; // "TMSR"
    private static final int FILE_VERSION = 2;
    private static final int CACHE_REGIONS = 48;
    /** Regions that failed to save are kept beyond the cache size, up to this many, before being given up. */
    private static final int CACHE_REGIONS_HARD = CACHE_REGIONS * 8;
    private static final int MAX_STORED_PAYLOAD = 1 << 20;
    private static final Pattern FILE_NAME = Pattern.compile("(-?\\d+)_(-?\\d+)\\.tmr");

    private final File root;
    private final LinkedHashMap<String, Region> cache = new LinkedHashMap<String, Region>(64, 0.75F, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Region> eldest) {
            if (size() <= CACHE_REGIONS) {
                return false;
            }
            if (write(eldest.getValue()) || size() > CACHE_REGIONS_HARD) {
                return true;
            }
            // Could not save it: keep it (and its data) for the next flush; an access moves it to the young end.
            get(eldest.getKey());
            return false;
        }
    };

    public RegionStore(File root) {
        this.root = root;
    }

    /**
     * @param overwrite replace known colours (live data) instead of only filling undiscovered tiles
     * @param markLive  remember that these tiles came from live data
     */
    public void merge(String bucket, String level, int regionX, int regionY, TileCodec.Tiles tiles, boolean overwrite, boolean markLive) {
        Region region = region(bucket, level, regionX, regionY);
        int flag = overwrite && markLive ? LIVE : 0;
        int[] colors = region.colors;
        for (int i = 0; i < tiles.size(); i++) {
            int index = tiles.indices[i];
            int value = tiles.rgb[i] | PRESENT | flag;
            if (colors[index] != value && (overwrite || colors[index] == 0)) {
                colors[index] = value;
                region.dirty = true;
            }
        }
    }

    /** The region's tiles ({@link #PRESENT} and {@link #LIVE} flags included), or null if nothing is stored. */
    public int[] colors(String bucket, String level, int regionX, int regionY) {
        String key = key(bucket, level, regionX, regionY);
        if (!cache.containsKey(key) && !file(bucket, level, regionX, regionY).isFile()) {
            return null;
        }
        return region(bucket, level, regionX, regionY).colors;
    }

    /** Every stored region of the bucket, or of one level of it when {@code onlyLevel} is set. */
    public List<RegionKey> list(String bucket, String onlyLevel) {
        LinkedHashSet<RegionKey> out = new LinkedHashSet<>();
        File[] levels = new File(root, bucket).listFiles(File::isDirectory);
        if (levels != null) {
            for (File levelFolder : levels) {
                String level = levelFolder.getName();
                if (!TeamMarker.isValidLevel(level) || onlyLevel != null && !onlyLevel.equals(level)) {
                    continue;
                }
                File[] files = levelFolder.listFiles();
                if (files == null) {
                    continue;
                }
                for (File file : files) {
                    Matcher matcher = FILE_NAME.matcher(file.getName());
                    if (matcher.matches()) {
                        out.add(new RegionKey(level, Integer.parseInt(matcher.group(1)), Integer.parseInt(matcher.group(2))));
                    }
                }
            }
        }
        for (Region region : cache.values()) {
            if (region.bucket.equals(bucket) && (onlyLevel == null || onlyLevel.equals(region.level))) {
                out.add(new RegionKey(region.level, region.regionX, region.regionY));
            }
        }
        return new ArrayList<>(out);
    }

    /**
     * Removes the tiles that still hold the values of {@code snapshot} (taken earlier with {@link #colors}), and the
     * region once it is empty. Tiles merged in since the snapshot stay.
     */
    public void removeMatching(String bucket, String level, int regionX, int regionY, int[] snapshot) {
        int[] colors = colors(bucket, level, regionX, regionY);
        if (colors == null) {
            return;
        }
        boolean empty = true;
        boolean changed = false;
        for (int i = 0; i < colors.length; i++) {
            if (colors[i] != 0 && colors[i] == snapshot[i]) {
                colors[i] = 0;
                changed = true;
            } else if (colors[i] != 0) {
                empty = false;
            }
        }
        if (empty) {
            delete(bucket, level, regionX, regionY);
        } else if (changed) {
            cache.get(key(bucket, level, regionX, regionY)).dirty = true;
        }
    }

    public void delete(String bucket, String level, int regionX, int regionY) {
        cache.remove(key(bucket, level, regionX, regionY));
        File file = file(bucket, level, regionX, regionY);
        if (file.isFile() && !file.delete()) {
            GameLog.warn.println("TeamMapSync: could not delete " + file);
        }
    }

    public void flushAll() {
        for (Region region : cache.values()) {
            write(region);
        }
    }

    public File bucketFolder(String bucket) {
        return new File(root, bucket);
    }

    /** Tiles of {@code colors} whose flags masked with {@code flagMask} equal {@code flagValue}, sorted by index. */
    public static TileCodec.Tiles tiles(int[] colors, int flagMask, int flagValue) {
        int count = 0;
        for (int color : colors) {
            if (color != 0 && (color & flagMask) == flagValue) {
                count++;
            }
        }
        int[] indices = new int[count];
        int[] rgb = new int[count];
        int n = 0;
        for (int i = 0; i < colors.length; i++) {
            int color = colors[i];
            if (color != 0 && (color & flagMask) == flagValue) {
                indices[n] = i;
                rgb[n++] = color & 0xFFFFFF;
            }
        }
        return new TileCodec.Tiles(indices, rgb);
    }

    private Region region(String bucket, String level, int regionX, int regionY) {
        String key = key(bucket, level, regionX, regionY);
        Region region = cache.get(key);
        if (region == null) {
            region = new Region(bucket, level, regionX, regionY);
            read(file(bucket, level, regionX, regionY), region.colors);
            cache.put(key, region);
        }
        return region;
    }

    /** @return true if the region is saved (or had nothing to save) */
    private boolean write(Region region) {
        if (!region.dirty) {
            return true;
        }
        File file = file(region.bucket, region.level, region.regionX, region.regionY);
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);
            out.writeInt(FILE_MAGIC);
            out.writeInt(FILE_VERSION);
            out.writeUTF(region.level);
            out.writeInt(region.regionX);
            out.writeInt(region.regionY);
            ArrayList<byte[]> groups = new ArrayList<>(2);
            ArrayList<Integer> flags = new ArrayList<>(2);
            for (int flag : new int[]{0, LIVE}) {
                TileCodec.Tiles tiles = tiles(region.colors, LIVE, flag);
                if (tiles.size() > 0) {
                    groups.add(TileCodec.encode(tiles.indices, tiles.rgb, tiles.size()));
                    flags.add(flag);
                }
            }
            out.writeInt(groups.size());
            for (int i = 0; i < groups.size(); i++) {
                out.writeInt(flags.get(i));
                out.writeInt(groups.get(i).length);
                out.write(groups.get(i));
            }
            out.flush();
            writeAtomically(file, bytes.toByteArray());
            region.dirty = false;
            return true;
        } catch (IOException e) {
            GameLog.warn.println("TeamMapSync: could not save " + file + ", will retry: " + e.getMessage());
            return false;
        }
    }

    private static void read(File file, int[] colors) {
        if (!file.isFile()) {
            return;
        }
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(Files.readAllBytes(file.toPath())))) {
            if (in.readInt() != FILE_MAGIC || in.readInt() != FILE_VERSION) {
                return;
            }
            in.readUTF();
            in.readInt();
            in.readInt();
            int groups = in.readInt();
            for (int g = 0; g < groups; g++) {
                int flag = in.readInt() & LIVE;
                int length = in.readInt();
                if (length < 0 || length > MAX_STORED_PAYLOAD) {
                    return;
                }
                byte[] payload = new byte[length];
                in.readFully(payload);
                TileCodec.Tiles tiles = TileCodec.decode(payload);
                for (int i = 0; i < tiles.size(); i++) {
                    colors[tiles.indices[i]] = tiles.rgb[i] | PRESENT | flag;
                }
            }
        } catch (IOException | DataFormatException e) {
            GameLog.warn.println("TeamMapSync: could not read " + file + ": " + e.getMessage());
        }
    }

    public static void writeAtomically(File file, byte[] data) throws IOException {
        File folder = file.getParentFile();
        if (!folder.isDirectory() && !folder.mkdirs()) {
            throw new IOException("Could not create " + folder);
        }
        File temp = new File(folder, file.getName() + ".tmp");
        Files.write(temp.toPath(), data);
        try {
            Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static String key(String bucket, String level, int regionX, int regionY) {
        return bucket + "/" + level + "/" + regionX + "/" + regionY;
    }

    private File file(String bucket, String level, int regionX, int regionY) {
        // Buckets are UUIDs or fixed names and level identifiers are [a-z0-9-+]: safe folder names everywhere.
        return new File(new File(new File(root, bucket), level), regionX + "_" + regionY + ".tmr");
    }

    /** One map region of one level. */
    public static final class RegionKey {
        public final String level;
        public final int regionX;
        public final int regionY;

        public RegionKey(String level, int regionX, int regionY) {
            this.level = level;
            this.regionX = regionX;
            this.regionY = regionY;
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof RegionKey)) {
                return false;
            }
            RegionKey other = (RegionKey) o;
            return regionX == other.regionX && regionY == other.regionY && level.equals(other.level);
        }

        @Override
        public int hashCode() {
            return (level.hashCode() * 31 + regionX) * 31 + regionY;
        }
    }

    private static final class Region {
        final String bucket;
        final String level;
        final int regionX;
        final int regionY;
        final int[] colors = new int[TileCodec.MAX_TILES];
        boolean dirty;

        Region(String bucket, String level, int regionX, int regionY) {
            this.bucket = bucket;
            this.level = level;
            this.regionX = regionX;
            this.regionY = regionY;
        }
    }
}

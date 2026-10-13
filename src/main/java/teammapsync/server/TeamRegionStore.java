package teammapsync.server;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;
import java.util.zip.DataFormatException;
import necesse.engine.GameLog;
import teammapsync.TeamMarker;
import teammapsync.codec.TileCodec;
import teammapsync.net.PacketTeamMapTiles;

/**
 * Every team's merged map, one file per team and map region. Only ever touched from the server worker thread.
 *
 * <p>Files hold the same {@link TileCodec} payload that goes over the network, so streaming a team's map to a
 * joining player is a file read with no recompression. Recently written regions stay in a small LRU cache so a
 * player walking around costs one decode per region, not one per update.
 */
final class TeamRegionStore {

    private static final int FILE_MAGIC = 0x544D5352; // "TMSR"
    private static final int FILE_VERSION = 1;
    private static final int CACHE_REGIONS = 48;
    /** Present tiles carry this bit so pure black (rgb 0) is still distinguishable from undiscovered. */
    private static final int PRESENT = 0x1000000;

    private final File root;
    private final LinkedHashMap<String, Region> cache = new LinkedHashMap<String, Region>(64, 0.75F, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Region> eldest) {
            if (size() > CACHE_REGIONS) {
                write(eldest.getValue());
                return true;
            }
            return false;
        }
    };

    TeamRegionStore(File root) {
        this.root = root;
    }

    void merge(int team, PacketTeamMapTiles tiles) {
        TileCodec.Tiles decoded;
        try {
            decoded = TileCodec.decode(tiles.payload);
        } catch (DataFormatException e) {
            return;
        }
        Region region = region(team, tiles.level, tiles.regionX, tiles.regionY);
        boolean overwrite = tiles.mode == PacketTeamMapTiles.MODE_LIVE;
        int[] colors = region.colors;
        for (int i = 0; i < decoded.size(); i++) {
            int index = decoded.indices[i];
            int value = decoded.rgb[i] | PRESENT;
            if (colors[index] != value && (overwrite || colors[index] == 0)) {
                colors[index] = value;
                region.dirty = true;
            }
        }
    }

    /** Hands every stored region of the team to {@code sink} as fill packets. */
    void stream(int team, Consumer<PacketTeamMapTiles> sink) {
        flushTeam(team);
        File[] levels = teamFolder(team).listFiles(File::isDirectory);
        if (levels == null) {
            return;
        }
        for (File levelFolder : levels) {
            File[] files = levelFolder.listFiles((dir, name) -> name.endsWith(".tmr"));
            if (files == null) {
                continue;
            }
            for (File file : files) {
                StoredRegion stored = read(file);
                if (stored != null) {
                    sink.accept(new PacketTeamMapTiles(PacketTeamMapTiles.MODE_FILL, stored.level, stored.regionX, stored.regionY, stored.payload));
                }
            }
        }
    }

    void flushAll() {
        for (Region region : cache.values()) {
            write(region);
        }
    }

    private void flushTeam(int team) {
        for (Region region : cache.values()) {
            if (region.team == team) {
                write(region);
            }
        }
    }

    private Region region(int team, String level, int regionX, int regionY) {
        String key = team + "/" + level + "/" + regionX + "/" + regionY;
        Region region = cache.get(key);
        if (region == null) {
            region = new Region(team, level, regionX, regionY);
            StoredRegion stored = read(file(team, level, regionX, regionY));
            if (stored != null) {
                try {
                    TileCodec.Tiles tiles = TileCodec.decode(stored.payload);
                    for (int i = 0; i < tiles.size(); i++) {
                        region.colors[tiles.indices[i]] = tiles.rgb[i] | PRESENT;
                    }
                } catch (DataFormatException e) {
                    GameLog.warn.println("TeamMapSync: discarding corrupt region " + key);
                }
            }
            cache.put(key, region);
        }
        return region;
    }

    private void write(Region region) {
        if (!region.dirty) {
            return;
        }
        int[] colors = region.colors;
        int count = 0;
        for (int color : colors) {
            if (color != 0) {
                count++;
            }
        }
        int[] indices = new int[count];
        int[] rgb = new int[count];
        int n = 0;
        for (int i = 0; i < colors.length; i++) {
            if (colors[i] != 0) {
                indices[n] = i;
                rgb[n++] = colors[i] & 0xFFFFFF;
            }
        }
        byte[] payload = TileCodec.encode(indices, rgb, count);
        File file = file(region.team, region.level, region.regionX, region.regionY);
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(payload.length + 64);
            DataOutputStream out = new DataOutputStream(bytes);
            out.writeInt(FILE_MAGIC);
            out.writeInt(FILE_VERSION);
            out.writeUTF(region.level);
            out.writeInt(region.regionX);
            out.writeInt(region.regionY);
            out.writeInt(payload.length);
            out.write(payload);
            out.flush();
            writeAtomically(file, bytes.toByteArray());
            region.dirty = false;
        } catch (IOException e) {
            GameLog.warn.println("TeamMapSync: could not save " + file + ": " + e.getMessage());
        }
    }

    private static StoredRegion read(File file) {
        if (!file.isFile()) {
            return null;
        }
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(Files.readAllBytes(file.toPath())))) {
            if (in.readInt() != FILE_MAGIC || in.readInt() != FILE_VERSION) {
                return null;
            }
            String level = in.readUTF();
            int regionX = in.readInt();
            int regionY = in.readInt();
            int length = in.readInt();
            if (!TeamMarker.isValidLevel(level) || length < 0 || length > PacketTeamMapTiles.MAX_PAYLOAD) {
                return null;
            }
            byte[] payload = new byte[length];
            in.readFully(payload);
            return new StoredRegion(level, regionX, regionY, payload);
        } catch (IOException e) {
            GameLog.warn.println("TeamMapSync: could not read " + file + ": " + e.getMessage());
            return null;
        }
    }

    static void writeAtomically(File file, byte[] data) throws IOException {
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

    File teamFolder(int team) {
        return new File(root, "team" + team);
    }

    private File file(int team, String level, int regionX, int regionY) {
        // Level identifiers are [a-z0-9-+], which is safe as a folder name on every platform.
        return new File(new File(teamFolder(team), level), regionX + "_" + regionY + ".tmr");
    }


    private static final class Region {
        final int team;
        final String level;
        final int regionX;
        final int regionY;
        final int[] colors = new int[TileCodec.MAX_TILES];
        boolean dirty;

        Region(int team, String level, int regionX, int regionY) {
            this.team = team;
            this.level = level;
            this.regionX = regionX;
            this.regionY = regionY;
        }
    }

    private static final class StoredRegion {
        final String level;
        final int regionX;
        final int regionY;
        final byte[] payload;

        StoredRegion(String level, int regionX, int regionY, byte[] payload) {
            this.level = level;
            this.regionX = regionX;
            this.regionY = regionY;
            this.payload = payload;
        }
    }
}

package teammapsync.client;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.zip.DataFormatException;
import necesse.engine.GameLog;
import necesse.engine.GlobalData;
import necesse.engine.network.Packet;
import necesse.engine.network.client.Client;
import necesse.engine.network.client.ClientLevelManager;
import necesse.engine.save.LoadData;
import necesse.engine.util.LevelIdentifier;
import necesse.level.maps.mapData.ClientDiscoveredMap;
import necesse.level.maps.mapData.ClientDiscoveredMapRegion;
import necesse.level.maps.mapData.DiscoveredMapData;
import teammapsync.TeamMarker;
import teammapsync.Workers;
import teammapsync.codec.TileCodec;
import teammapsync.net.PacketTeamMapHello;
import teammapsync.net.PacketTeamMapTiles;
import teammapsync.net.PacketTeamMarker;

/**
 * Client half: sends this player's discoveries, applies teammates' tiles and keeps shared markers in sync.
 *
 * <p>The game thread only snapshots recorded tiles, sends queued packets and writes received tiles into the open
 * map under a per-tick budget. Compression, decompression and all map file access run on one worker thread.
 */
public final class TeamMapClient {

    private static final int FLUSH_TICKS = 40;
    private static final int MARKER_POLL_TICKS = 20;
    /** Tiles written into the open map per game tick; a full region spreads over a few ticks. */
    private static final int APPLY_TILES_PER_TICK = 4096;
    /** Backlog upload per tick (about 60 KB/s), below what the server accepts per player. */
    private static final int UPLOAD_BYTES_PER_TICK = 3 * 1024;
    /** Tiles kept in memory for levels whose size is not known yet (never visited this session). */
    private static final int MAX_PENDING_TILES = 1 << 20;

    private static final ExecutorService worker = Workers.single("TeamMapSync-Client");
    private static TeamMapClient instance;

    private final Client client;
    private final TeamMarkers markers;
    private boolean helloSent;
    private int team = -1;
    /** Bumped per session; read by worker tasks to abandon stale work. */
    private volatile int generation;
    private long ticks;

    private final ConcurrentLinkedQueue<Packet> liveOut = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<Queued> bulkOut = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<Incoming> incoming = new ConcurrentLinkedQueue<>();
    private Incoming applying;

    private ClientDiscoveredMap trackedMap;
    private volatile String currentLevel;
    private final Map<String, int[]> levelSizes = new ConcurrentHashMap<>();
    private final Map<String, ConcurrentLinkedQueue<Incoming>> pending = new ConcurrentHashMap<>();
    private final AtomicInteger pendingTiles = new AtomicInteger();

    private TeamMapClient(Client client) {
        this.client = client;
        this.markers = new TeamMarkers(client);
    }

    // ---------------------------------------------------------------- hooks

    public static void tick(Client client) {
        TeamMapClient self = instance;
        if (self == null || self.client != client) {
            MapRecorder.enabled = false;
            MapRecorder.clear();
            self = instance = new TeamMapClient(client);
        }
        self.tick();
    }

    /** The active session for this client, or null when not sharing with a team. */
    public static TeamMapClient active(Client client) {
        TeamMapClient self = instance;
        return self != null && self.client == client && self.team != -1 ? self : null;
    }

    public static void onSession(Client client, int team) {
        TeamMapClient self = instance;
        if (self != null && self.client == client) {
            self.startSession(team);
        }
    }

    public static void onTiles(Client client, PacketTeamMapTiles packet) {
        TeamMapClient self = instance;
        if (self == null || self.client != client || !TeamMarker.isValidLevel(packet.level)) {
            return;
        }
        int generation = self.generation;
        worker.execute(() -> {
            try {
                TileCodec.Tiles tiles = TileCodec.decode(packet.payload);
                self.incoming.add(new Incoming(generation, packet.level, packet.regionX, packet.regionY,
                        packet.mode == PacketTeamMapTiles.MODE_LIVE, tiles));
            } catch (DataFormatException e) {
                GameLog.warn.println("TeamMapSync: dropped corrupt map tiles: " + e.getMessage());
            }
        });
    }

    public static void onMarker(Client client, PacketTeamMarker packet) {
        TeamMapClient self = instance;
        if (self == null || self.client != client) {
            return;
        }
        if (packet.marker == null) {
            self.markers.onRemove(packet.id);
        } else {
            self.markers.onSet(packet.marker);
        }
    }

    public static void beforeMapSave(ClientLevelManager levelManager) {
        TeamMapClient self = instance;
        if (self != null && self.client.levelManager == levelManager) {
            self.markers.hideForSave();
        }
    }

    public static void afterMapSave(ClientLevelManager levelManager) {
        TeamMapClient self = instance;
        if (self != null && self.client.levelManager == levelManager) {
            self.markers.restoreAfterSave();
        }
    }

    public boolean isShared(ClientLevelManager.MapMarker marker) {
        return markers.isShared(marker);
    }

    public void setShared(ClientLevelManager.MapMarker marker, boolean shared) {
        if (shared) {
            markers.share(marker);
        } else {
            markers.unshare(marker);
        }
    }

    // ---------------------------------------------------------------- game thread

    private void tick() {
        if (client.isSingleplayer() || client.getSlot() == -1 || client.isDisconnected()) {
            return;
        }
        if (!helloSent) {
            if (client.loading.isDone() && client.getLevel() != null && client.getPlayer() != null) {
                helloSent = true;
                client.network.sendPacket(new PacketTeamMapHello());
            }
            return;
        }
        ticks++;
        trackLevel();
        applyIncoming();
        if (team == -1) {
            return;
        }
        sendQueued();
        if (ticks % FLUSH_TICKS == 0) {
            flushRecorded();
        }
        if (ticks % MARKER_POLL_TICKS == 0) {
            markers.poll();
        }
    }

    private void startSession(int newTeam) {
        if (newTeam == team) {
            return;
        }
        if (team != -1) {
            markers.reset();
        }
        team = newTeam;
        generation++;
        liveOut.clear();
        bulkOut.clear();
        MapRecorder.clear();
        MapRecorder.enabled = newTeam != -1;
        if (newTeam != -1) {
            // Flush the open level so the backlog scan sees it; vanilla does the same every minute.
            client.levelManager.saveMap();
            int generation = this.generation;
            worker.execute(() -> uploadBacklog(newTeam, generation));
        }
    }

    private void flushRecorded() {
        ArrayList<MapRecorder.Delta> deltas = MapRecorder.drain();
        if (deltas.isEmpty()) {
            return;
        }
        worker.execute(() -> {
            for (MapRecorder.Delta delta : deltas) {
                byte[] payload = TileCodec.encode(delta.indices, delta.rgb, delta.indices.length);
                liveOut.add(new PacketTeamMapTiles(PacketTeamMapTiles.MODE_LIVE, delta.level, delta.regionX, delta.regionY, payload));
            }
        });
    }

    private void sendQueued() {
        Packet packet;
        while ((packet = liveOut.poll()) != null) {
            client.network.sendPacket(packet);
        }
        int budget = UPLOAD_BYTES_PER_TICK;
        while (budget > 0) {
            Queued queued = bulkOut.poll();
            if (queued == null) {
                break;
            }
            if (queued.generation != generation) {
                continue;
            }
            if (queued.packet != null) {
                client.network.sendPacket(queued.packet);
                budget -= queued.packet.payload.length;
            } else {
                worker.execute(queued.after);
            }
        }
    }

    private void trackLevel() {
        ClientDiscoveredMap map = vanillaMap(client.levelManager);
        if (map == trackedMap) {
            return;
        }
        trackedMap = map;
        if (map == null) {
            currentLevel = null;
            return;
        }
        String level = map.level.getIdentifier().stringID;
        levelSizes.put(level, new int[]{map.level.tileWidth, map.level.tileHeight});
        currentLevel = level;
        ConcurrentLinkedQueue<Incoming> waiting = pending.remove(level);
        if (waiting != null) {
            for (Incoming in : waiting) {
                pendingTiles.addAndGet(-in.tiles.size());
                incoming.add(in);
            }
        }
    }

    private void applyIncoming() {
        int budget = APPLY_TILES_PER_TICK;
        while (budget > 0) {
            Incoming in = applying != null ? applying : incoming.poll();
            if (in == null) {
                return;
            }
            applying = null;
            if (in.generation != generation && !in.overwrite) {
                continue; // backlog of a previous team
            }
            ClientDiscoveredMap map = trackedMap;
            if (map == null || !in.level.equals(currentLevel)) {
                // Re-merging tiles already written to the open map is harmless; merging skips equal colours.
                worker.execute(() -> mergeIntoFile(in));
                continue;
            }
            ClientDiscoveredMapRegion region = region(map, in.regionX, in.regionY);
            if (region == null) {
                continue;
            }
            int width = region.getTileWidth();
            int height = region.getTileHeight();
            int[] indices = in.tiles.indices;
            int[] rgb = in.tiles.rgb;
            boolean changed = false;
            int end = Math.min(indices.length, in.cursor + budget);
            for (int i = in.cursor; i < end; i++) {
                int x = indices[i] & 0xFF;
                int y = indices[i] >>> 8;
                if (x >= width || y >= height) {
                    continue;
                }
                int current = region.getRGBA(x, y);
                boolean apply = in.overwrite ? current == 0 || (current & 0xFFFFFF) != rgb[i] : current == 0;
                if (apply) {
                    region.setRGBA(x, y, 0xFF000000 | rgb[i]);
                    changed = true;
                }
            }
            budget -= end - in.cursor;
            in.cursor = end;
            if (changed) {
                region.tickTextureUpdate();
            }
            if (in.cursor < indices.length) {
                applying = in;
            }
        }
    }

    // ---------------------------------------------------------------- worker thread

    /** Merges tiles of a level the player is not on into the vanilla map file, like the cartographer's table does. */
    private void mergeIntoFile(Incoming in) {
        if (in.level.equals(currentLevel)) {
            incoming.add(in); // the player arrived on that level meanwhile; apply it to the open map instead
            return;
        }
        LevelIdentifier identifier = new LevelIdentifier(in.level);
        File file = client.levelManager.getLevelWorldMapDataFile(identifier, in.regionX, in.regionY);
        DiscoveredMapData data;
        long previousModified = 0;
        if (file.isFile()) {
            previousModified = file.lastModified();
            data = new DiscoveredMapData(0, 0);
            try {
                data.applySaveData(new LoadData(file), true);
            } catch (Exception e) {
                return;
            }
        } else {
            int[] size = levelSizes.get(in.level);
            if (size == null) {
                // A missing file needs the region's size, which only the level knows; wait until it is visited.
                if (pendingTiles.addAndGet(in.tiles.size()) <= MAX_PENDING_TILES) {
                    pending.computeIfAbsent(in.level, k -> new ConcurrentLinkedQueue<>()).add(in);
                } else {
                    pendingTiles.addAndGet(-in.tiles.size());
                }
                return;
            }
            int width = regionSpan(size[0], in.regionX);
            int height = regionSpan(size[1], in.regionY);
            if (width <= 0 || height <= 0) {
                return;
            }
            data = new DiscoveredMapData(width, height);
        }

        int width = data.getTileWidth();
        int height = data.getTileHeight();
        boolean changed = false;
        for (int i = 0; i < in.tiles.size(); i++) {
            int x = in.tiles.indices[i] & 0xFF;
            int y = in.tiles.indices[i] >>> 8;
            if (x >= width || y >= height) {
                continue;
            }
            int current = data.getRGB(x, y);
            int rgb = in.tiles.rgb[i];
            if (in.overwrite ? current == 0 || (current & 0xFFFFFF) != rgb : current == 0) {
                changed |= data.setRGBA(x, y, 0xFF000000 | rgb);
            }
        }
        if (!changed) {
            return;
        }
        File folder = file.getParentFile();
        File temp = new File(folder, file.getName() + ".tmp");
        try {
            if (!folder.isDirectory() && !folder.mkdirs()) {
                return;
            }
            data.saveToFileSystem((id, rx, ry) -> temp, identifier, in.regionX, in.regionY, false);
            moveAtomically(temp, file);
            // Keep the old timestamp so the next backlog upload does not resend what came from the team.
            file.setLastModified(previousModified > 0 ? previousModified : 1L);
        } catch (IOException e) {
            GameLog.warn.println("TeamMapSync: could not save " + file + ": " + e.getMessage());
        }
    }

    /**
     * Sends map files changed since the last upload for this world and team: exploration made before the mod was
     * installed, before joining the team, or while no teammate could receive it.
     */
    private void uploadBacklog(int team, int generation) {
        File mapsRoot = new File(GlobalData.appDataPath(), "saves/maps/" + client.getWorldUniqueID());
        File stateFile = new File(GlobalData.appDataPath(), "teammapsync/client/" + client.getWorldUniqueID() + ".state");
        long since = readUploadState(stateFile, team);
        long started = System.currentTimeMillis();
        File[] levels = mapsRoot.listFiles(File::isDirectory);
        if (levels != null) {
            for (File levelFolder : levels) {
                String level = levelFolder.getName();
                if (!TeamMarker.isValidLevel(level)) {
                    continue;
                }
                File[] files = levelFolder.listFiles((dir, name) -> name.endsWith(".mapdata"));
                if (files == null) {
                    continue;
                }
                for (File file : files) {
                    if (generation != this.generation) {
                        return;
                    }
                    Matcher matcher = ClientLevelManager.mapRegionFilePattern.matcher(file.getName());
                    if (!matcher.matches() || file.lastModified() <= since) {
                        continue;
                    }
                    byte[] payload = encodeFile(file);
                    if (payload != null) {
                        PacketTeamMapTiles packet = new PacketTeamMapTiles(PacketTeamMapTiles.MODE_FILL, level,
                                Integer.parseInt(matcher.group(1)), Integer.parseInt(matcher.group(2)), payload);
                        bulkOut.add(new Queued(generation, packet, null));
                    }
                }
            }
        }
        bulkOut.add(new Queued(generation, null, () -> writeUploadState(stateFile, team, started)));
    }

    private static byte[] encodeFile(File file) {
        DiscoveredMapData data = new DiscoveredMapData(0, 0);
        try {
            data.applySaveData(new LoadData(file), true);
        } catch (Exception e) {
            return null;
        }
        int width = Math.min(256, data.getTileWidth());
        int height = Math.min(256, data.getTileHeight());
        int[] indices = new int[width * height];
        int[] rgb = new int[width * height];
        int count = 0;
        // Row-major by y then x gives increasing x | y << 8 indices.
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int color = data.getRGB(x, y);
                if (color != 0) {
                    indices[count] = x | y << 8;
                    rgb[count++] = color & 0xFFFFFF;
                }
            }
        }
        return count == 0 ? null : TileCodec.encode(indices, rgb, count);
    }

    private static long readUploadState(File file, int team) {
        try {
            String[] parts = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8).trim().split(" ");
            return Integer.parseInt(parts[0]) == team ? Long.parseLong(parts[1]) : 0L;
        } catch (Exception e) {
            return 0L;
        }
    }

    private static void writeUploadState(File file, int team, long uploadedAt) {
        try {
            File folder = file.getParentFile();
            if (!folder.isDirectory() && !folder.mkdirs()) {
                return;
            }
            File temp = new File(folder, file.getName() + ".tmp");
            Files.write(temp.toPath(), (team + " " + uploadedAt).getBytes(StandardCharsets.UTF_8));
            moveAtomically(temp, file);
        } catch (IOException e) {
            GameLog.warn.println("TeamMapSync: could not save " + file + ": " + e.getMessage());
        }
    }

    private static void moveAtomically(File from, File to) throws IOException {
        try {
            Files.move(from.toPath(), to.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            Files.move(from.toPath(), to.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** Width of map region {@code region} along an axis of {@code levelSize} tiles (0 or less means unbounded). */
    private static int regionSpan(int levelSize, int region) {
        if (levelSize <= 0) {
            return 256;
        }
        return region < 0 ? 0 : Math.min(256, levelSize - region * 256);
    }

    // ---------------------------------------------------------------- vanilla access

    private static Field mapField;
    private static Method getRegionMethod;

    /** The saved map (not the debug cheat map) of the open level; vanilla keeps it private. */
    private static ClientDiscoveredMap vanillaMap(ClientLevelManager levelManager) {
        try {
            if (mapField == null) {
                Field field = ClientLevelManager.class.getDeclaredField("map");
                field.setAccessible(true);
                mapField = field;
            }
            return (ClientDiscoveredMap) mapField.get(levelManager);
        } catch (ReflectiveOperationException e) {
            return null;
        }
    }

    private static ClientDiscoveredMapRegion region(ClientDiscoveredMap map, int regionX, int regionY) {
        try {
            if (getRegionMethod == null) {
                Method method = ClientDiscoveredMap.class.getDeclaredMethod("getRegion", int.class, int.class, boolean.class, boolean.class);
                method.setAccessible(true);
                getRegionMethod = method;
            }
            return (ClientDiscoveredMapRegion) getRegionMethod.invoke(map, regionX, regionY, true, false);
        } catch (ReflectiveOperationException e) {
            return null;
        }
    }

    private static final class Incoming {
        final int generation;
        final String level;
        final int regionX;
        final int regionY;
        final boolean overwrite;
        final TileCodec.Tiles tiles;
        int cursor;

        Incoming(int generation, String level, int regionX, int regionY, boolean overwrite, TileCodec.Tiles tiles) {
            this.generation = generation;
            this.level = level;
            this.regionX = regionX;
            this.regionY = regionY;
            this.overwrite = overwrite;
            this.tiles = tiles;
        }
    }

    /** A backlog packet, or (packet null) a step to run on the worker once everything before it was sent. */
    private static final class Queued {
        final int generation;
        final PacketTeamMapTiles packet;
        final Runnable after;

        Queued(int generation, PacketTeamMapTiles packet, Runnable after) {
            this.generation = generation;
            this.packet = packet;
            this.after = after;
        }
    }
}

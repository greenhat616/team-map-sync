package teammapsync.client;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.zip.DataFormatException;
import necesse.engine.GameLog;
import necesse.engine.GlobalData;
import necesse.engine.network.client.Client;
import necesse.engine.network.client.ClientLevelLoading;
import necesse.engine.network.client.ClientLevelManager;
import necesse.engine.save.LoadData;
import necesse.gfx.forms.presets.EditMapMarkerForm;
import necesse.level.maps.mapData.ClientDiscoveredMap;
import necesse.level.maps.mapData.ClientDiscoveredMapRegion;
import necesse.level.maps.mapData.DiscoveredMapData;
import teammapsync.TeamMarker;
import teammapsync.Workers;
import teammapsync.codec.TileCodec;
import teammapsync.net.OrderedBuffer;
import teammapsync.net.PacketTeamMapAck;
import teammapsync.net.PacketTeamMapTiles;
import teammapsync.net.PacketTeamMarker;
import teammapsync.net.ReliableChannel;
import teammapsync.net.SeqList;
import teammapsync.net.SeqWindow;
import teammapsync.store.RegionStore;

/**
 * Client half: sends this player's discoveries, applies teammates' tiles and keeps shared markers in sync.
 *
 * <p>The game thread only snapshots recorded tiles, sends queued packets and writes received tiles into the open
 * map under a per-tick budget. Compression, decompression and all file access run on one worker thread.
 *
 * <p>Vanilla's transport loses packets, so the client sends a heartbeat until the server's session announcement
 * arrives, drops data of other sessions, acknowledges what it receives and sends its own data through a
 * {@link ReliableChannel}. Local map files only count as uploaded once the server acknowledged every packet.
 *
 * <p>Tiles for a level the player is not on go to an inbox of this mod's own files instead of vanilla's map files,
 * which vanilla may load or save at any moment; the inbox is replayed into the open map when the player enters
 * that level.
 */
public final class TeamMapClient {

    /** Upload rate (about 40 KB/s), below what the server accepts per player. */
    private static final int SEND_BYTES_PER_TICK = 2048;
    private static final int MAX_IN_FLIGHT_BYTES = 24 * 1024;
    private static final int STATUS_TICKS = 20;
    private static final int ACK_FLUSH_TICKS = 2;
    private static final int FLUSH_TICKS = 40;
    private static final int MARKER_POLL_TICKS = 10;
    private static final int SAVE_TICKS = 20 * 10;
    private static final int INBOX_FLUSH_TICKS = 20 * 30;
    /** Tiles written into the open map per game tick; a full region spreads over a few ticks. */
    private static final int APPLY_TILES_PER_TICK = 4096;
    private static final int BACKLOG_LOW_WATER = 32;
    private static final int BACKLOG_BATCH_FILES = 4;
    private static final int INBOX_BATCH_REGIONS = 2;
    private static final String INBOX = "inbox";

    private static final ExecutorService worker = Workers.single("TeamMapSync-Client");
    private static TeamMapClient instance;

    private final Client client;
    private final TeamMarkers markers;
    private final ReliableChannel channel = new ReliableChannel(SEND_BYTES_PER_TICK, MAX_IN_FLIGHT_BYTES);
    /** Set when a newer client instance took over; worker tasks of this one then stop. */
    private volatile boolean closed;
    /** 0 until the server announced a session; read by worker tasks to abandon stale work. */
    private volatile int epoch;
    private String teamKey;
    private SeqWindow received = new SeqWindow();
    private final SeqList acks = new SeqList();
    private OrderedBuffer<Runnable> orderedIn = new OrderedBuffer<>();
    private long ticks;
    private long lastStatusTick = -STATUS_TICKS;
    private boolean busy;
    private boolean busySent;

    private final ConcurrentLinkedQueue<Incoming> incoming = new ConcurrentLinkedQueue<>();
    private Incoming applying;
    /**
     * Tiles of the open level written by live data or the player's own discovery since the player entered it.
     * Inbox data for the level is older than all of that, so its replay must not overwrite these tiles.
     */
    private final HashMap<String, BitSet> fresh = new HashMap<>();
    /** The save started by {@link #persistPending}, so a later caller that must wait can wait for it. */
    private Future<?> persisted;
    private ClientDiscoveredMap trackedMap;
    /** Read by worker tasks to skip work for a level the player already left. */
    private volatile String currentLevel;

    private File dataRoot;
    private String mapsFolder;
    /** Worker thread only. */
    private RegionStore inbox;
    private final ConcurrentLinkedQueue<RegionStore.RegionKey> inboxDrain = new ConcurrentLinkedQueue<>();
    private volatile boolean inboxBusy;
    /** Worker thread only. */
    private UploadState uploadState;
    private final ConcurrentLinkedQueue<BacklogFile> backlog = new ConcurrentLinkedQueue<>();
    private volatile boolean backlogBusy;

    private TeamMapClient(Client client) {
        this.client = client;
        this.markers = new TeamMarkers(client, this::sendMarker);
    }

    // ---------------------------------------------------------------- hooks

    public static void tick(Client client) {
        TeamMapClient self = instance;
        if (self == null || self.client != client) {
            if (self != null) {
                self.close();
            }
            MapRecorder.enabled = false;
            MapRecorder.clear();
            self = instance = new TeamMapClient(client);
        }
        self.tick();
    }

    /** The active session for this client, or null when not sharing with a team. */
    public static TeamMapClient active(Client client) {
        TeamMapClient self = instance;
        return self != null && self.client == client && self.teamKey != null ? self : null;
    }

    public static void onSession(Client client, int epoch, String teamKey) {
        TeamMapClient self = get(client);
        if (self == null) {
            return;
        }
        if (epoch > self.epoch) {
            self.startSession(epoch, teamKey);
        }
        self.lastStatusTick = -STATUS_TICKS; // confirm it right away, also when this was a repeat
    }

    public static void onAck(Client client, PacketTeamMapAck packet) {
        TeamMapClient self = get(client);
        if (self != null && packet.epoch == self.epoch) {
            for (int seq : packet.seqs) {
                self.channel.ack(seq);
            }
        }
    }

    public static void onTiles(Client client, PacketTeamMapTiles packet) {
        TeamMapClient self = get(client);
        if (self != null) {
            self.receive(packet.epoch, packet.seq, packet.ordinal, () -> self.decodeTiles(packet));
        }
    }

    public static void onMarker(Client client, PacketTeamMarker packet) {
        TeamMapClient self = get(client);
        if (self == null) {
            return;
        }
        self.receive(packet.epoch, packet.seq, packet.ordinal, () -> {
            if (packet.op == PacketTeamMarker.OP_SET && packet.marker != null) {
                self.markers.onSet(packet.marker);
            } else if (packet.op == PacketTeamMarker.OP_REMOVE) {
                self.markers.onRemove(packet.id);
            } else if (packet.op == PacketTeamMarker.OP_REJECT) {
                self.markers.onReject(packet.id);
            }
        });
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

    /** The {@code deletemapdata} command wiped the local map and markers; that does not delete team markers. */
    public static void onMapDataDeleted(ClientLevelManager levelManager) {
        TeamMapClient self = instance;
        if (self != null && self.client.levelManager == levelManager) {
            self.markers.forget();
        }
    }

    public boolean isShared(ClientLevelManager.MapMarker marker) {
        return markers.isShared(marker);
    }

    public void setShared(ClientLevelManager.MapMarker marker, boolean shared, EditMapMarkerForm form) {
        markers.setShared(marker, shared, form);
    }

    public void editing(ClientLevelManager.MapMarker marker, EditMapMarkerForm form) {
        markers.editing(marker, form);
    }

    private static TeamMapClient get(Client client) {
        TeamMapClient self = instance;
        return self != null && self.client == client ? self : null;
    }

    // ---------------------------------------------------------------- game thread

    private void tick() {
        if (client.isDisconnected()) {
            persistPending(false);
            return;
        }
        if (client.isSingleplayer() || client.getSlot() == -1) {
            return;
        }
        if (!client.loading.isDone() || client.getLevel() == null || client.getPlayer() == null) {
            return;
        }
        if (dataRoot == null) {
            String world = String.valueOf(client.getWorldUniqueID());
            dataRoot = new File(GlobalData.appDataPath(), "teammapsync/client/" + world);
            mapsFolder = "saves/maps/" + world;
            File inboxRoot = dataRoot;
            worker.execute(() -> inbox = new RegionStore(inboxRoot));
        }
        ticks++;
        busy = vanillaLoadingBusy();
        trackLevel();
        applyIncoming();
        sendStatus();
        if (ticks % INBOX_FLUSH_TICKS == 0) {
            worker.execute(() -> inbox.flushAll());
        }
        if (teamKey == null) {
            return;
        }
        channel.tick(System.currentTimeMillis(), busy, Integer.MAX_VALUE, client.network::sendPacket);
        refillBacklog();
        if (ticks % FLUSH_TICKS == 0) {
            flushRecorded();
        }
        if (ticks % MARKER_POLL_TICKS == 0) {
            markers.poll();
        }
        if (ticks % SAVE_TICKS == 0) {
            worker.execute(this::saveUploadState);
        }
    }

    private void close() {
        persistPending(false);
        closed = true;
        channel.reset(-1);
        worker.execute(() -> {
            // Only now: decodes queued before the save above still belong to this session.
            epoch = -1;
            saveUploadState();
            if (inbox != null) {
                inbox.flushAll();
            }
        });
    }

    /**
     * Checks a reliable packet from the server, queues its acknowledgement (also for repeats, whose first
     * acknowledgement may have been lost) and runs {@code action} once: right away for bulk data, or after every
     * earlier ordered packet for ordered data.
     */
    private void receive(int packetEpoch, int seq, int ordinal, Runnable action) {
        if (packetEpoch != epoch || epoch <= 0 || !received.isAcceptable(seq)
                || ordinal < 0 || ordinal > 0 && !orderedIn.isAcceptable(ordinal)) {
            return;
        }
        acks.add(seq);
        if (!received.accept(seq)) {
            return;
        }
        if (ordinal > 0) {
            orderedIn.receive(ordinal, action, Runnable::run);
        } else {
            action.run();
        }
    }

    private void decodeTiles(PacketTeamMapTiles packet) {
        if (!TeamMarker.isValidLevel(packet.level)) {
            return;
        }
        // Live data overwrites, so it must stay in order; bulk data only fills and may arrive in any order.
        boolean live = packet.mode == PacketTeamMapTiles.MODE_LIVE && packet.ordinal > 0;
        int sessionEpoch = epoch;
        // The single worker keeps submission order, so ordered packets reach the apply queue in order.
        worker.execute(() -> {
            if (sessionEpoch != epoch) {
                return;
            }
            try {
                TileCodec.Tiles tiles = TileCodec.decode(packet.payload);
                if (sessionEpoch == epoch) {
                    incoming.add(new Incoming(sessionEpoch, packet.level, packet.regionX, packet.regionY, live, tiles, null));
                }
            } catch (DataFormatException e) {
                GameLog.warn.println("TeamMapSync: dropped corrupt map tiles: " + e.getMessage());
            }
        });
    }

    /**
     * Received tiles not yet written into the map would be lost when the connection ends; they go to the inbox
     * and are replayed next time. Runs once, from vanilla's disconnect (waiting for the worker, since the game may
     * be closing) or from the first tick after it. Inbox replays need nothing: their entries are only removed once
     * applied.
     */
    private void persistPending(boolean wait) {
        if (persisted != null || dataRoot == null) {
            if (wait) {
                await(persisted);
            }
            return;
        }
        // Ordered packets waiting for a lost predecessor are decoded too; losing them would be worse.
        orderedIn.flushWaiting(Runnable::run);
        Incoming first = applying;
        applying = null;
        int sessionEpoch = epoch;
        // Queued behind every decode already submitted, so their results are in the queue by then.
        persisted = worker.submit(() -> {
            ArrayList<Incoming> pending = new ArrayList<>();
            if (first != null) {
                pending.add(first);
            }
            Incoming in;
            while ((in = incoming.poll()) != null) {
                pending.add(in);
            }
            for (Incoming item : pending) {
                if (item.replay == null && item.epoch == sessionEpoch) {
                    inbox.merge(INBOX, item.level, item.regionX, item.regionY, item.tiles, item.overwrite, true);
                }
            }
            inbox.flushAll();
            saveUploadState();
        });
        if (wait) {
            await(persisted);
        }
    }

    private static void await(Future<?> task) {
        if (task == null) {
            return;
        }
        try {
            task.get(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            GameLog.warn.println("TeamMapSync: could not save received map tiles in time: " + e);
        }
    }

    /** Vanilla is disconnecting this client (also when the game closes). */
    public static void onDisconnect(Client client) {
        TeamMapClient self = get(client);
        if (self != null) {
            self.persistPending(true);
        }
    }

    /** Heartbeat (which also asks for or confirms the session) and acknowledgements. */
    private void sendStatus() {
        boolean due = ticks - lastStatusTick >= STATUS_TICKS || busy != busySent;
        if (!due && (acks.isEmpty() || ticks % ACK_FLUSH_TICKS != 0)) {
            return;
        }
        do {
            client.network.sendPacket(new PacketTeamMapAck(epoch, busy, acks.take(PacketTeamMapAck.MAX_SEQS)));
        } while (!acks.isEmpty());
        lastStatusTick = ticks;
        busySent = busy;
    }

    private void startSession(int newEpoch, String newTeamKey) {
        markers.reset();
        epoch = newEpoch;
        teamKey = newTeamKey;
        channel.reset(newEpoch);
        received = new SeqWindow();
        orderedIn = new OrderedBuffer<>();
        acks.clear();
        backlog.clear();
        MapRecorder.clear();
        MapRecorder.enabled = newTeamKey != null;
        if (newTeamKey != null) {
            // Flush the open level so the backlog scan sees it; vanilla does the same every minute.
            client.levelManager.saveMap();
        }
        File root = dataRoot;
        String maps = mapsFolder;
        worker.execute(() -> scanBacklog(newEpoch, newTeamKey, root, maps));
    }

    private void flushRecorded() {
        ArrayList<MapRecorder.Delta> deltas = MapRecorder.drain();
        if (deltas.isEmpty()) {
            return;
        }
        for (MapRecorder.Delta delta : deltas) {
            if (delta.level.equals(currentLevel)) {
                BitSet bits = freshBits(delta.level, delta.regionX, delta.regionY);
                for (int index : delta.indices) {
                    bits.set(index);
                }
            }
        }
        int sessionEpoch = epoch;
        worker.execute(() -> {
            for (MapRecorder.Delta delta : deltas) {
                for (byte[] band : TileCodec.encodeBands(delta.indices, delta.rgb, delta.indices.length)) {
                    channel.enqueue(sessionEpoch, true, (seq, ordinal) -> new PacketTeamMapTiles(sessionEpoch, seq, ordinal,
                            PacketTeamMapTiles.MODE_LIVE, delta.level, delta.regionX, delta.regionY, band), band.length, null);
                }
            }
        });
    }

    private void sendMarker(int op, UUID id, TeamMarker marker, Runnable onAcked) {
        int sessionEpoch = epoch;
        channel.enqueue(sessionEpoch, true, (seq, ordinal) -> new PacketTeamMarker(sessionEpoch, seq, ordinal, op, 0, id, marker),
                PacketTeamMarker.MAX_SIZE, onAcked);
    }

    /** Hands the worker the next few local map files once the upload queue runs low. */
    private void refillBacklog() {
        if (backlogBusy || backlog.isEmpty() || channel.bulkQueued() >= BACKLOG_LOW_WATER) {
            return;
        }
        List<BacklogFile> batch = new ArrayList<>(BACKLOG_BATCH_FILES);
        BacklogFile file;
        while (batch.size() < BACKLOG_BATCH_FILES && (file = backlog.poll()) != null) {
            batch.add(file);
        }
        int sessionEpoch = epoch;
        backlogBusy = true;
        worker.execute(() -> {
            try {
                for (BacklogFile item : batch) {
                    uploadFile(item, sessionEpoch);
                }
            } finally {
                backlogBusy = false;
            }
        });
    }

    private boolean vanillaLoadingBusy() {
        ClientLevelLoading loading = client.levelManager.loading();
        return loading != null && loading.getRegionsRequestedCount() + loading.getRegionsLoadQueueCount() > 0;
    }

    private void trackLevel() {
        ClientDiscoveredMap map = vanillaMap(client.levelManager);
        if (map == trackedMap) {
            return;
        }
        trackedMap = map;
        inboxDrain.clear();
        fresh.clear();
        if (map == null) {
            currentLevel = null;
            return;
        }
        String level = map.level.getIdentifier().stringID;
        currentLevel = level;
        worker.execute(() -> {
            if (level.equals(currentLevel)) {
                inboxDrain.addAll(inbox.list(INBOX, level));
            }
        });
    }

    private void applyIncoming() {
        int budget = APPLY_TILES_PER_TICK;
        while (budget > 0) {
            Incoming in = applying != null ? applying : incoming.poll();
            if (in == null) {
                refillFromInbox();
                return;
            }
            applying = null;
            if (in.replay == null && in.epoch != epoch) {
                continue; // received in an earlier session; the new team's data must not be pre-empted by it
            }
            ClientDiscoveredMap map = trackedMap;
            if (map == null || !in.level.equals(currentLevel)) {
                if (in.replay == null) {
                    worker.execute(() -> {
                        if (!closed) {
                            inbox.merge(INBOX, in.level, in.regionX, in.regionY, in.tiles, in.overwrite, true);
                        }
                    });
                }
                // An unfinished replay stays in the inbox and is replayed on the next visit.
                continue;
            }
            ClientDiscoveredMapRegion region = region(map, in.regionX, in.regionY);
            if (region == null) {
                finished(in);
                continue;
            }
            int width = region.getTileWidth();
            int height = region.getTileHeight();
            int[] indices = in.tiles.indices;
            int[] rgb = in.tiles.rgb;
            BitSet freshTiles = freshBits(in.level, in.regionX, in.regionY);
            if (in.replay != null) {
                markOwnDiscoveries();
            }
            boolean changed = false;
            int end = Math.min(indices.length, in.cursor + budget);
            for (int i = in.cursor; i < end; i++) {
                int x = indices[i] & 0xFF;
                int y = indices[i] >>> 8;
                if (x >= width || y >= height) {
                    continue;
                }
                int current = region.getRGBA(x, y);
                boolean apply;
                if (!in.overwrite || current == 0) {
                    apply = current == 0;
                } else if (in.replay != null) {
                    apply = (current & 0xFFFFFF) != rgb[i] && !freshTiles.get(indices[i]);
                } else {
                    apply = (current & 0xFFFFFF) != rgb[i];
                }
                if (apply) {
                    region.setRGBA(x, y, 0xFF000000 | rgb[i]);
                    changed = true;
                }
                // Newer than anything in the inbox: live data, and fill data that reached an unknown tile.
                if (in.replay == null && (in.overwrite || apply)) {
                    freshTiles.set(indices[i]);
                }
            }
            budget -= end - in.cursor;
            in.cursor = end;
            if (changed) {
                region.tickTextureUpdate();
            }
            if (in.cursor < indices.length) {
                applying = in;
            } else {
                finished(in);
            }
        }
    }

    /** Once every part of an inbox replay is written into the map, its inbox entry can go. */
    private void finished(Incoming in) {
        InboxReplay replay = in.replay;
        if (replay != null && --replay.pending == 0) {
            worker.execute(() -> inbox.removeMatching(INBOX, replay.level, replay.regionX, replay.regionY, replay.snapshot));
        }
    }

    /** Own discoveries not yet flushed are newer than inbox data too. */
    private void markOwnDiscoveries() {
        String level = currentLevel;
        MapRecorder.forEachPending((tileLevel, regionX, regionY, index) -> {
            if (tileLevel.equals(level)) {
                freshBits(tileLevel, regionX, regionY).set(index);
            }
        });
    }

    private BitSet freshBits(String level, int regionX, int regionY) {
        return fresh.computeIfAbsent(level + "/" + regionX + "/" + regionY, k -> new BitSet(TileCodec.MAX_TILES));
    }

    /** Once received tiles are applied, replays the next inbox regions of the open level. */
    private void refillFromInbox() {
        if (inboxBusy || inboxDrain.isEmpty()) {
            return;
        }
        List<RegionStore.RegionKey> batch = new ArrayList<>(INBOX_BATCH_REGIONS);
        RegionStore.RegionKey key;
        while (batch.size() < INBOX_BATCH_REGIONS && (key = inboxDrain.poll()) != null) {
            batch.add(key);
        }
        inboxBusy = true;
        worker.execute(() -> {
            try {
                for (RegionStore.RegionKey region : batch) {
                    int[] colors = inbox.colors(INBOX, region.level, region.regionX, region.regionY);
                    if (colors == null) {
                        continue;
                    }
                    TileCodec.Tiles live = RegionStore.tiles(colors, RegionStore.LIVE, RegionStore.LIVE);
                    TileCodec.Tiles fill = RegionStore.tiles(colors, RegionStore.LIVE, 0);
                    int parts = (fill.size() > 0 ? 1 : 0) + (live.size() > 0 ? 1 : 0);
                    if (parts == 0) {
                        inbox.delete(INBOX, region.level, region.regionX, region.regionY);
                        continue;
                    }
                    // The inbox entry stays until the replay is fully applied, so quitting midway loses nothing.
                    InboxReplay replay = new InboxReplay(region, colors.clone(), parts);
                    if (fill.size() > 0) {
                        incoming.add(new Incoming(0, region.level, region.regionX, region.regionY, false, fill, replay));
                    }
                    if (live.size() > 0) {
                        incoming.add(new Incoming(0, region.level, region.regionX, region.regionY, true, live, replay));
                    }
                }
            } finally {
                inboxBusy = false;
            }
        });
    }

    // ---------------------------------------------------------------- worker thread

    /**
     * Queues the local map files the server has not acknowledged for this team: exploration made before the mod
     * was installed, before joining the team, or whose upload was lost.
     */
    private void scanBacklog(int sessionEpoch, String team, File root, String maps) {
        saveUploadState();
        uploadState = null;
        if (team == null || closed) {
            return;
        }
        UploadState state = UploadState.load(new File(root, "uploaded-" + team + ".txt"));
        uploadState = state;
        File[] levels = new File(GlobalData.appDataPath(), maps).listFiles(File::isDirectory);
        if (levels == null) {
            return;
        }
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
                if (sessionEpoch != epoch) {
                    return;
                }
                Matcher matcher = ClientLevelManager.mapRegionFilePattern.matcher(file.getName());
                if (!matcher.matches()) {
                    continue;
                }
                String key = level + "/" + matcher.group(1) + "_" + matcher.group(2);
                long modified = file.lastModified();
                long size = file.length();
                if (!state.isCurrent(key, modified, size)) {
                    backlog.add(new BacklogFile(file, level, Integer.parseInt(matcher.group(1)), Integer.parseInt(matcher.group(2)),
                            key, modified, size));
                }
            }
        }
    }

    private void uploadFile(BacklogFile item, int sessionEpoch) {
        UploadState state = uploadState;
        if (sessionEpoch != epoch || state == null) {
            return;
        }
        DiscoveredMapData data = new DiscoveredMapData(0, 0);
        try {
            data.applySaveData(new LoadData(item.file), true);
        } catch (Exception e) {
            return; // not recorded, so it is tried again on the next join
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
        List<byte[]> bands = TileCodec.encodeBands(indices, rgb, count);
        if (bands.isEmpty()) {
            state.record(item.key, item.modified, item.size);
            return;
        }
        AtomicInteger remaining = new AtomicInteger(bands.size());
        Runnable onAcked = () -> {
            if (remaining.decrementAndGet() == 0) {
                worker.execute(() -> state.record(item.key, item.modified, item.size));
            }
        };
        for (byte[] band : bands) {
            channel.enqueue(sessionEpoch, false, (seq, ordinal) -> new PacketTeamMapTiles(sessionEpoch, seq, ordinal, PacketTeamMapTiles.MODE_FILL,
                    item.level, item.regionX, item.regionY, band), band.length, onAcked);
        }
    }

    private void saveUploadState() {
        if (uploadState != null) {
            uploadState.save();
        }
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
        /** Session it was received in; unused for inbox replays. */
        final int epoch;
        final String level;
        final int regionX;
        final int regionY;
        final boolean overwrite;
        final TileCodec.Tiles tiles;
        /** Set when this replays inbox data rather than carrying newly received data. */
        final InboxReplay replay;
        int cursor;

        Incoming(int epoch, String level, int regionX, int regionY, boolean overwrite, TileCodec.Tiles tiles, InboxReplay replay) {
            this.epoch = epoch;
            this.replay = replay;
            this.level = level;
            this.regionX = regionX;
            this.regionY = regionY;
            this.overwrite = overwrite;
            this.tiles = tiles;
        }
    }

    /** One inbox region being replayed. {@code pending} is game thread only; the snapshot is never modified. */
    private static final class InboxReplay {
        final String level;
        final int regionX;
        final int regionY;
        final int[] snapshot;
        int pending;

        InboxReplay(RegionStore.RegionKey region, int[] snapshot, int pending) {
            this.level = region.level;
            this.regionX = region.regionX;
            this.regionY = region.regionY;
            this.snapshot = snapshot;
            this.pending = pending;
        }
    }

    private static final class BacklogFile {
        final File file;
        final String level;
        final int regionX;
        final int regionY;
        final String key;
        final long modified;
        final long size;

        BacklogFile(File file, String level, int regionX, int regionY, String key, long modified, long size) {
            this.file = file;
            this.level = level;
            this.regionX = regionX;
            this.regionY = regionY;
            this.key = key;
            this.modified = modified;
            this.size = size;
        }
    }
}

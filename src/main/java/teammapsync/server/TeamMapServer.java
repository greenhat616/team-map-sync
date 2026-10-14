package teammapsync.server;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.zip.DataFormatException;
import necesse.engine.GameLog;
import necesse.engine.GlobalData;
import necesse.engine.network.server.Server;
import necesse.engine.network.server.ServerClient;
import teammapsync.TeamMarker;
import teammapsync.Workers;
import teammapsync.codec.TileCodec;
import teammapsync.net.PacketTeamMapAck;
import teammapsync.net.PacketTeamMapSession;
import teammapsync.net.PacketTeamMapTiles;
import teammapsync.net.OrderedBuffer;
import teammapsync.net.PacketTeamMarker;
import teammapsync.net.ReliableChannel;
import teammapsync.net.SeqList;
import teammapsync.net.SeqWindow;
import teammapsync.store.RegionStore;

/**
 * Server half: relays map tiles and markers between teammates and keeps each team's merged map for players who
 * were offline.
 *
 * <p>Built to stay out of the tick budget. On the server thread it only forwards already-compressed bytes,
 * enqueues work and drains send queues under a byte budget; decoding, merging, encoding and every file access run
 * on one low-priority worker thread.
 *
 * <p>Vanilla's transport loses packets, so every session is announced until the client confirms it, every data
 * packet carries the session's epoch and is acknowledged and resent through a {@link ReliableChannel}, and stored
 * data is streamed lazily so a joining player's backlog never sits in memory as a whole.
 */
public final class TeamMapServer {

    /** Send rate per player (about 50 KB/s) and for the whole server; kept modest so vanilla traffic keeps room. */
    private static final int SEND_BYTES_PER_CLIENT_TICK = 2560;
    private static final int SEND_BYTES_PER_SERVER_TICK = 16 * 1024;
    private static final int MAX_IN_FLIGHT_BYTES = 32 * 1024;
    /** Live data waiting for one player; beyond this it cannot keep up and is resynced from the store. */
    private static final int MAX_ORDERED_BACKLOG_BYTES = 256 * 1024;
    /** Relayed fill data waiting for one player; beyond this regions are sent from the store later instead. */
    private static final int MAX_BULK_RELAY_BYTES = 256 * 1024;
    /** Live data held back during a resync; beyond this those regions join the next resync round. */
    private static final int MAX_HELD_BYTES = 256 * 1024;
    private static final long OWN_ECHO_AFTER_SNAPSHOT_MS = 5000;
    /** Receive allowance per player; packets beyond it are not acknowledged, so the client sends them again later. */
    private static final int RECEIVE_BYTES_PER_SECOND = 96 * 1024;
    private static final int RECEIVE_BURST_BYTES = 192 * 1024;
    private static final int TEAM_CHECK_TICKS = 10;
    private static final int SESSION_RESEND_TICKS = 20;
    private static final int ACK_FLUSH_TICKS = 2;
    private static final long STATUS_TIMEOUT_MS = 5000;
    private static final int STREAM_LOW_WATER = 32;
    private static final int STREAM_BATCH_REGIONS = 4;
    private static final int MARKER_SAVE_TICKS = 100;
    private static final int REGION_FLUSH_TICKS = 20 * 30;

    private static TeamMapServer instance;
    /** Static so sessions of a restarted server in the same process never reuse an epoch. */
    private static int nextEpoch;

    private final Server server;
    private final ExecutorService worker = Workers.single("TeamMapSync-Server");
    private final HashMap<Integer, Session> sessions = new HashMap<>();
    private RegionStore regions;
    private TeamMarkerStore markers;
    private long ticks;
    private int roundRobin;

    private TeamMapServer(Server server) {
        this.server = server;
    }

    public static void start(Server server) {
        stop(instance == null ? null : instance.server);
        instance = new TeamMapServer(server);
    }

    public static void stop(Server server) {
        TeamMapServer current = instance;
        if (current == null || current.server != server) {
            return;
        }
        instance = null;
        if (current.regions != null) {
            current.markers.saveDirty(current.worker);
            current.worker.execute(current.regions::flushAll);
        }
        current.worker.shutdown();
        try {
            if (!current.worker.awaitTermination(10, TimeUnit.SECONDS)) {
                GameLog.warn.println("TeamMapSync: timed out saving team maps");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static TeamMapServer get(Server server) {
        TeamMapServer current = instance;
        return current != null && current.server == server ? current : null;
    }

    public static void onDisconnect(ServerClient client) {
        TeamMapServer self = instance;
        if (self != null && client != null) {
            Session session = self.sessions.get(client.slot);
            if (session != null && session.client == client) {
                self.sessions.remove(client.slot);
                session.epoch = -1; // stops its stream tasks
            }
        }
    }

    /** Heartbeat and acknowledgements from a client; the first one starts its session. */
    public static void onStatus(Server server, ServerClient client, PacketTeamMapAck packet) {
        TeamMapServer self = get(server);
        if (self == null || client == null || !self.ensureStores()) {
            return;
        }
        Session session = self.sessions.get(client.slot);
        if (session == null || session.client != client) {
            session = new Session(client);
            self.sessions.put(client.slot, session);
            self.startSession(session);
        }
        session.lastStatusMs = System.currentTimeMillis();
        session.busy = packet.busy;
        if (packet.epoch != session.epoch) {
            session.confirmed = false; // it missed the latest announcement; the tick repeats it
            return;
        }
        session.confirmed = true;
        for (int seq : packet.seqs) {
            session.channel.ack(seq);
        }
    }

    public static void onTiles(Server server, ServerClient client, PacketTeamMapTiles tiles) {
        TeamMapServer self = get(server);
        Session session = self == null ? null : self.session(client);
        if (session == null) {
            return;
        }
        self.receive(session, tiles.epoch, tiles.seq, tiles.ordinal, tiles.payload.length, () -> self.applyTiles(session, tiles));
    }

    public static void onMarker(Server server, ServerClient client, PacketTeamMarker packet) {
        TeamMapServer self = get(server);
        Session session = self == null ? null : self.session(client);
        if (session == null) {
            return;
        }
        self.receive(session, packet.epoch, packet.seq, packet.ordinal, PacketTeamMarker.MAX_SIZE, () -> self.applyMarker(session, packet));
    }

    public static void tick(Server server) {
        TeamMapServer self = get(server);
        if (self != null) {
            self.tick();
        }
    }

    // ---------------------------------------------------------------- server thread

    private Session session(ServerClient client) {
        Session session = client == null ? null : sessions.get(client.slot);
        return session != null && session.client == client ? session : null;
    }

    /**
     * Checks a reliable packet from a client, queues its acknowledgement and runs {@code action} once: right away
     * for bulk data, or after every earlier ordered packet for ordered data.
     */
    private void receive(Session session, int epoch, int seq, int ordinal, int size, Runnable action) {
        if (epoch != session.epoch) {
            return;
        }
        session.confirmed = true;
        if (session.client.getTeamID() != session.teamID) {
            startSession(session);
            return;
        }
        if (session.teamKey == null || session.receiveTokens < size || !session.received.isAcceptable(seq)
                || ordinal < 0 || ordinal > 0 && !session.orderedIn.isAcceptable(ordinal)) {
            return; // not acknowledged: an honest client sends it again later
        }
        session.receiveTokens -= size;
        session.acks.add(seq);
        if (!session.received.accept(seq)) {
            return;
        }
        if (ordinal > 0) {
            session.orderedIn.receive(ordinal, action, Runnable::run);
        } else {
            action.run();
        }
    }

    /** Relays a client's tiles to its online teammates and merges them into the team's store. */
    private void applyTiles(Session session, PacketTeamMapTiles tiles) {
        if (tiles.payload.length == 0 || !TeamMarker.isValidLevel(tiles.level)) {
            return;
        }
        // Live data overwrites, so it must stay in order; bulk data only fills and may arrive in any order.
        boolean live = tiles.mode == PacketTeamMapTiles.MODE_LIVE && tiles.ordinal > 0;
        int mode = live ? PacketTeamMapTiles.MODE_LIVE : PacketTeamMapTiles.MODE_FILL;
        String team = session.teamKey;
        for (Session other : sessions.values()) {
            if (other != session && team.equals(other.teamKey)) {
                relay(other, tiles, live);
            }
        }
        if (live && session.echoesOwnLive()) {
            // The player's resync snapshot may be older than this, its own update: send it back after the snapshot.
            relay(session, tiles, true);
        }
        // Queued after the relays above, so a resync snapshot taken later (also on the worker) includes these tiles.
        worker.execute(() -> {
            try {
                regions.merge(team, tiles.level, tiles.regionX, tiles.regionY, TileCodec.decode(tiles.payload), live, false);
            } catch (DataFormatException e) {
                GameLog.warn.println("TeamMapSync: dropped corrupt map tiles: " + e.getMessage());
            }
        });
    }

    private void relay(Session to, PacketTeamMapTiles tiles, boolean live) {
        RegionStore.RegionKey region = new RegionStore.RegionKey(tiles.level, tiles.regionX, tiles.regionY);
        if (!live) {
            if (to.channel.bulkQueuedBytes() > MAX_BULK_RELAY_BYTES) {
                // The receiver is behind: send it this region from the store later instead of queueing more.
                if (to.overflowStream.add(region)) {
                    to.stream.add(region);
                }
                return;
            }
            enqueueTiles(to, PacketTeamMapTiles.MODE_FILL, region, tiles.payload, false);
        } else if (to.resync != null) {
            to.resync.hold(tiles, region);
        } else {
            enqueueTiles(to, PacketTeamMapTiles.MODE_LIVE, region, tiles.payload, true);
        }
    }

    /** Relayed live data is tagged with its region, so it can be dropped for a resync if the receiver falls behind. */
    private static void enqueueTiles(Session to, int mode, RegionStore.RegionKey region, byte[] payload, boolean ordered) {
        enqueueTiles(to, mode, region, payload, ordered, ordered ? region : null, null);
    }

    private static void enqueueTiles(Session to, int mode, RegionStore.RegionKey region, byte[] payload, boolean ordered,
                                     Object tag, Runnable onAcked) {
        int epoch = to.epoch;
        to.channel.enqueue(epoch, ordered, (seq, ordinal) -> new PacketTeamMapTiles(epoch, seq, ordinal, mode, region.level,
                region.regionX, region.regionY, payload), payload.length, onAcked, tag);
    }

    /**
     * The player cannot keep up with live data (teammates explore faster than its connection carries). Queued live
     * packets for it are dropped and replaced by the current stored state of those regions, sent as live data in
     * the ordered stream: fill data could not correct terrain the player already knows. Live data arriving until
     * that snapshot is queued is held back, since the snapshot is older than it.
     */
    private void startResync(Session session, Set<RegionStore.RegionKey> regions) {
        Resync resync = session.resync;
        if (resync == null) {
            resync = session.resync = new Resync();
        }
        regions.addAll(resync.takeHeldRegions());
        resync.done = false;
        int epoch = session.epoch;
        String team = session.teamKey;
        Resync current = resync;
        worker.execute(() -> {
            for (RegionStore.RegionKey region : regions) {
                if (session.epoch != epoch) {
                    return;
                }
                int[] colors = this.regions.colors(team, region.level, region.regionX, region.regionY);
                if (colors != null) {
                    TileCodec.Tiles tiles = RegionStore.tiles(colors, 0, 0);
                    current.snapshot.add(new Object[]{region, TileCodec.encodeBands(tiles.indices, tiles.rgb, tiles.size())});
                }
            }
            current.done = true;
        });
    }

    /** Queues a finished resync snapshot, then the live data held back meanwhile (or starts the next round). */
    @SuppressWarnings("unchecked")
    private void finishResync(Session session) {
        Resync resync = session.resync;
        Object[] item;
        // Untagged, so a large snapshot is never dropped and restarted; it is what the receiver has to catch up on.
        Runnable onAcked = () -> {
            if (--session.snapshotUnacked == 0) {
                session.echoOwnUntilMs = System.currentTimeMillis() + OWN_ECHO_AFTER_SNAPSHOT_MS;
            }
        };
        while ((item = resync.snapshot.poll()) != null) {
            RegionStore.RegionKey region = (RegionStore.RegionKey) item[0];
            for (byte[] band : (List<byte[]>) item[1]) {
                session.snapshotUnacked++;
                enqueueTiles(session, PacketTeamMapTiles.MODE_LIVE, region, band, true, null, onAcked);
            }
        }
        if (!resync.next.isEmpty()) {
            HashSet<RegionStore.RegionKey> next = new HashSet<>(resync.next);
            resync.next.clear();
            startResync(session, next);
            return;
        }
        for (Object[] held : resync.held) {
            enqueueTiles(session, PacketTeamMapTiles.MODE_LIVE, (RegionStore.RegionKey) held[0], (byte[]) held[1], true);
        }
        session.resync = null;
    }

    private void applyMarker(Session session, PacketTeamMarker packet) {
        String team = session.teamKey;
        if (packet.op == PacketTeamMarker.OP_SET) {
            TeamMarkerStore.Stored stored = packet.marker != null && packet.marker.isValid() ? markers.set(team, packet.marker) : null;
            if (stored == null) {
                sendMarker(session, PacketTeamMarker.OP_REJECT, 0, packet.id, null);
            } else {
                broadcastMarker(team, PacketTeamMarker.OP_SET, stored.revision, stored.marker.id, stored.marker);
            }
        } else if (packet.op == PacketTeamMarker.OP_REMOVE) {
            long revision = markers.remove(team, packet.id);
            if (revision != -1) {
                broadcastMarker(team, PacketTeamMarker.OP_REMOVE, revision, packet.id, null);
            }
        }
    }

    private void tick() {
        ticks++;
        if (regions == null) {
            return;
        }
        if (ticks % MARKER_SAVE_TICKS == 0) {
            markers.saveDirty(worker);
        }
        if (ticks % REGION_FLUSH_TICKS == 0) {
            worker.execute(regions::flushAll);
        }
        if (sessions.isEmpty()) {
            return;
        }

        long now = System.currentTimeMillis();
        ArrayList<Session> order = new ArrayList<>(sessions.values());
        int first = roundRobin++ % order.size();
        int budget = SEND_BYTES_PER_SERVER_TICK;
        for (int i = 0; i < order.size(); i++) {
            Session session = order.get((first + i) % order.size());
            session.receiveTokens = Math.min(RECEIVE_BURST_BYTES, session.receiveTokens + RECEIVE_BYTES_PER_SECOND / 20.0);
            if (ticks % TEAM_CHECK_TICKS == 0 && session.client.getTeamID() != session.teamID) {
                startSession(session);
            }
            if (session.channel.droppableQueuedBytes() > MAX_ORDERED_BACKLOG_BYTES) {
                HashSet<RegionStore.RegionKey> dropped = new HashSet<>();
                for (Object tag : session.channel.dropOrdered()) {
                    dropped.add((RegionStore.RegionKey) tag);
                }
                if (session.resync != null) {
                    session.resync.next.addAll(dropped);
                } else if (!dropped.isEmpty()) {
                    GameLog.warn.println("TeamMapSync: " + session.client.getName() + " cannot keep up with live map data, resyncing "
                            + dropped.size() + " regions");
                    startResync(session, dropped);
                }
            }
            if (session.resync != null && session.resync.done) {
                finishResync(session);
            }
            if (!session.confirmed) {
                if (ticks - session.sessionSentTick >= SESSION_RESEND_TICKS) {
                    sendSession(session);
                }
                continue;
            }
            if (now - session.lastStatusMs > STATUS_TIMEOUT_MS) {
                session.busy = false;
            }
            if (ticks % ACK_FLUSH_TICKS == 0) {
                while (!session.acks.isEmpty()) {
                    session.client.sendPacket(new PacketTeamMapAck(session.epoch, false, session.acks.take(PacketTeamMapAck.MAX_SEQS)));
                }
            }
            refillStream(session);
            budget -= session.channel.tick(now, session.busy, Math.max(0, budget), session.client::sendPacket);
        }
    }

    private void startSession(Session session) {
        session.teamID = session.client.getTeamID();
        session.teamKey = TeamKeys.key(server, session.teamID);
        session.epoch = ++nextEpoch;
        session.confirmed = false;
        session.channel.reset(session.epoch);
        session.received = new SeqWindow();
        session.acks.clear();
        session.orderedIn = new OrderedBuffer<>();
        session.stream.clear();
        session.overflowStream.clear();
        session.resync = null;
        session.snapshotUnacked = 0;
        session.echoOwnUntilMs = 0;
        sendSession(session);
        if (session.teamKey == null) {
            return;
        }
        for (TeamMarkerStore.Stored stored : markers.markers(session.teamKey)) {
            sendMarker(session, PacketTeamMarker.OP_SET, stored.revision, stored.marker.id, stored.marker);
        }
        int epoch = session.epoch;
        String team = session.teamKey;
        session.streamBusy = true;
        worker.execute(() -> {
            if (session.epoch == epoch) {
                session.stream.addAll(regions.list(team, null));
                session.streamBusy = false;
            }
        });
    }

    private void sendSession(Session session) {
        session.sessionSentTick = ticks;
        session.client.sendPacket(new PacketTeamMapSession(session.epoch, session.teamKey));
    }

    /** Hands the worker the next few stored regions once the session's send queue runs low. */
    private void refillStream(Session session) {
        if (session.streamBusy || session.stream.isEmpty() || session.channel.bulkQueued() >= STREAM_LOW_WATER) {
            return;
        }
        List<RegionStore.RegionKey> batch = new ArrayList<>(STREAM_BATCH_REGIONS);
        RegionStore.RegionKey key;
        while (batch.size() < STREAM_BATCH_REGIONS && (key = session.stream.poll()) != null) {
            session.overflowStream.remove(key);
            batch.add(key);
        }
        int epoch = session.epoch;
        String team = session.teamKey;
        session.streamBusy = true;
        worker.execute(() -> {
            for (RegionStore.RegionKey region : batch) {
                if (session.epoch != epoch) {
                    return;
                }
                int[] colors = regions.colors(team, region.level, region.regionX, region.regionY);
                if (colors == null) {
                    continue;
                }
                TileCodec.Tiles tiles = RegionStore.tiles(colors, 0, 0);
                for (byte[] band : TileCodec.encodeBands(tiles.indices, tiles.rgb, tiles.size())) {
                    session.channel.enqueue(epoch, false, (seq, ordinal) -> new PacketTeamMapTiles(epoch, seq, ordinal, PacketTeamMapTiles.MODE_FILL,
                            region.level, region.regionX, region.regionY, band), band.length, null);
                }
            }
            if (session.epoch == epoch) {
                session.streamBusy = false;
            }
        });
    }

    private void broadcastMarker(String team, int op, long revision, UUID id, TeamMarker marker) {
        for (Session other : sessions.values()) {
            if (team.equals(other.teamKey)) {
                sendMarker(other, op, revision, id, marker);
            }
        }
    }

    private void sendMarker(Session session, int op, long revision, UUID id, TeamMarker marker) {
        int epoch = session.epoch;
        session.channel.enqueue(epoch, true, (seq, ordinal) -> new PacketTeamMarker(epoch, seq, ordinal, op, revision, id, marker),
                PacketTeamMarker.MAX_SIZE, null);
    }

    private boolean ensureStores() {
        if (regions == null) {
            if (server.world == null || server.world.worldEntity == null) {
                return false;
            }
            // "v2": stores of earlier versions were keyed by vanilla team numbers, which are not stable.
            File root = new File(GlobalData.appDataPath(), "teammapsync/" + server.world.worldEntity.getUniqueID() + "/v2");
            regions = new RegionStore(root);
            markers = new TeamMarkerStore(regions);
        }
        return true;
    }

    /** A running resync of one session; server thread except {@link #snapshot} and {@link #done}. */
    private static final class Resync {
        /** Live packets that arrived after the snapshot was requested, in order: {region, payload}. */
        final ArrayList<Object[]> held = new ArrayList<>();
        int heldBytes;
        /** Regions for the next round: dropped or overflowing while this round ran. */
        final HashSet<RegionStore.RegionKey> next = new HashSet<>();
        /** Filled by the worker: {region, bands}. */
        final ConcurrentLinkedQueue<Object[]> snapshot = new ConcurrentLinkedQueue<>();
        volatile boolean done;

        void hold(PacketTeamMapTiles tiles, RegionStore.RegionKey region) {
            if (heldBytes + tiles.payload.length > MAX_HELD_BYTES) {
                next.addAll(takeHeldRegions());
                next.add(region);
                return;
            }
            held.add(new Object[]{region, tiles.payload});
            heldBytes += tiles.payload.length;
        }

        /** Held packets' regions; a snapshot requested now includes their tiles (their merges were queued first). */
        HashSet<RegionStore.RegionKey> takeHeldRegions() {
            HashSet<RegionStore.RegionKey> regions = new HashSet<>();
            for (Object[] held : this.held) {
                regions.add((RegionStore.RegionKey) held[0]);
            }
            held.clear();
            heldBytes = 0;
            return regions;
        }
    }

    private static final class Session {
        /**
         * While a resync is running, its snapshot is unacknowledged, or shortly after (the client may not have
         * applied it yet), the player's own live updates are sent back to it after the snapshot.
         */
        boolean echoesOwnLive() {
            return resync != null || snapshotUnacked > 0 || System.currentTimeMillis() < echoOwnUntilMs;
        }

        final ServerClient client;
        int teamID = -2;
        String teamKey;
        /** Read by worker tasks to abandon work of an ended session. */
        volatile int epoch;
        boolean confirmed;
        long sessionSentTick;
        long lastStatusMs;
        boolean busy;
        double receiveTokens = RECEIVE_BURST_BYTES;
        final ReliableChannel channel = new ReliableChannel(SEND_BYTES_PER_CLIENT_TICK, MAX_IN_FLIGHT_BYTES);
        SeqWindow received = new SeqWindow();
        final SeqList acks = new SeqList();
        OrderedBuffer<Runnable> orderedIn = new OrderedBuffer<>();
        /** Stored regions still to send, filled by the worker. */
        final ConcurrentLinkedQueue<RegionStore.RegionKey> stream = new ConcurrentLinkedQueue<>();
        volatile boolean streamBusy;
        /** Regions added to {@link #stream} because relayed fill data overflowed; avoids duplicates. */
        final HashSet<RegionStore.RegionKey> overflowStream = new HashSet<>();
        Resync resync;
        /** Resync snapshot packets not acknowledged yet. */
        int snapshotUnacked;
        long echoOwnUntilMs;

        Session(ServerClient client) {
            this.client = client;
        }
    }
}

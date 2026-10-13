package teammapsync.server;

import java.io.File;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import necesse.engine.GameLog;
import necesse.engine.GlobalData;
import necesse.engine.network.Packet;
import necesse.engine.network.server.Server;
import necesse.engine.network.server.ServerClient;
import teammapsync.TeamMarker;
import teammapsync.Workers;
import teammapsync.net.PacketTeamMapSession;
import teammapsync.net.PacketTeamMapTiles;
import teammapsync.net.PacketTeamMarker;

/**
 * Server half: relays map tiles and markers between teammates and keeps each team's merged map for players who
 * were offline.
 *
 * <p>Built to stay out of the tick budget. On the server thread it only forwards already-compressed bytes,
 * enqueues work and drains send queues under a byte budget; decoding, merging, encoding and every file access run
 * on one low-priority worker thread.
 */
public final class TeamMapServer {

    /** Upload allowance per player; live exploration is a few KB/s, a joining player's backlog is more. */
    private static final int RECEIVE_BYTES_PER_SECOND = 96 * 1024;
    private static final int RECEIVE_BURST_BYTES = 384 * 1024;
    /** Backlog (full sync) sending per player and for the whole server, per tick. */
    private static final int BULK_BYTES_PER_CLIENT_TICK = 6 * 1024;
    private static final int BULK_BYTES_PER_SERVER_TICK = 48 * 1024;
    private static final int TEAM_CHECK_TICKS = 20;
    private static final int MARKER_SAVE_TICKS = 100;
    private static final int REGION_FLUSH_TICKS = 20 * 30;

    private static TeamMapServer instance;

    private final Server server;
    private final ExecutorService worker = Workers.single("TeamMapSync-Server");
    private final HashMap<Integer, Session> sessions = new HashMap<>();
    private TeamRegionStore regions;
    private TeamMarkerStore markers;
    private long ticks;

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
            }
        }
    }

    public static void onHello(Server server, ServerClient client) {
        TeamMapServer self = get(server);
        if (self == null || client == null || !self.ensureStores()) {
            return;
        }
        Session session = self.sessions.get(client.slot);
        if (session == null || session.client != client) {
            session = new Session(client);
            self.sessions.put(client.slot, session);
        }
        self.startSession(session);
    }

    public static void onTiles(Server server, ServerClient client, PacketTeamMapTiles tiles) {
        TeamMapServer self = get(server);
        Session session = self == null || client == null ? null : self.sessions.get(client.slot);
        if (session == null || session.client != client || session.team == -1) {
            return;
        }
        int size = tiles.payload.length;
        if (size == 0 || !TeamMarker.isValidLevel(tiles.level) || session.receiveTokens < size) {
            return;
        }
        session.receiveTokens -= size;

        int team = session.team;
        PacketTeamMapTiles relay = new PacketTeamMapTiles(tiles.mode, tiles.level, tiles.regionX, tiles.regionY, tiles.payload);
        for (Session other : self.sessions.values()) {
            if (other != session && other.team == team) {
                if (tiles.mode == PacketTeamMapTiles.MODE_LIVE) {
                    other.live.add(relay);
                } else {
                    other.bulk.add(new Queued(other.generation, relay));
                }
            }
        }
        self.worker.execute(() -> self.regions.merge(team, relay));
    }

    public static void onMarker(Server server, ServerClient client, PacketTeamMarker packet) {
        TeamMapServer self = get(server);
        Session session = self == null || client == null ? null : self.sessions.get(client.slot);
        if (session == null || session.client != client || session.team == -1) {
            return;
        }
        Packet relay;
        if (packet.marker == null) {
            if (!self.markers.remove(session.team, packet.id)) {
                return;
            }
            relay = PacketTeamMarker.remove(packet.id);
        } else {
            if (!packet.marker.isValid()) {
                return;
            }
            TeamMarker stored = self.markers.set(session.team, packet.marker);
            if (stored == null) {
                return;
            }
            relay = PacketTeamMarker.set(stored);
        }
        for (Session other : self.sessions.values()) {
            if (other != session && other.team == session.team) {
                other.live.add(relay);
            }
        }
    }

    public static void tick(Server server) {
        TeamMapServer self = get(server);
        if (self != null && !self.sessions.isEmpty()) {
            self.tick();
        }
    }

    private void tick() {
        ticks++;
        if (ticks % TEAM_CHECK_TICKS == 0) {
            for (Session session : sessions.values()) {
                if (session.client.getTeamID() != session.team) {
                    startSession(session);
                }
            }
        }
        if (ticks % MARKER_SAVE_TICKS == 0) {
            markers.saveDirty(worker);
        }
        if (ticks % REGION_FLUSH_TICKS == 0) {
            worker.execute(regions::flushAll);
        }

        int serverBudget = BULK_BYTES_PER_SERVER_TICK;
        for (Session session : sessions.values()) {
            session.receiveTokens = Math.min(RECEIVE_BURST_BYTES, session.receiveTokens + RECEIVE_BYTES_PER_SECOND / 20);
            // Live updates are already rate limited where they enter (the sender's receive budget).
            Packet packet;
            while ((packet = session.live.poll()) != null) {
                session.client.sendPacket(packet);
            }
            int clientBudget = BULK_BYTES_PER_CLIENT_TICK;
            while (clientBudget > 0 && serverBudget > 0) {
                Queued queued = session.bulk.poll();
                if (queued == null) {
                    break;
                }
                if (queued.generation == session.generation) {
                    session.client.sendPacket(queued.packet);
                    clientBudget -= queued.packet.payload.length;
                    serverBudget -= queued.packet.payload.length;
                }
            }
        }
    }

    private void startSession(Session session) {
        int team = session.client.getTeamID();
        session.team = team;
        session.generation++;
        session.live.clear();
        session.bulk.clear();
        session.client.sendPacket(new PacketTeamMapSession(team));
        if (team == -1) {
            return;
        }
        for (TeamMarker marker : markers.markers(team)) {
            session.live.add(PacketTeamMarker.set(marker));
        }
        int generation = session.generation;
        worker.execute(() -> regions.stream(team, packet -> session.bulk.add(new Queued(generation, packet))));
    }

    private boolean ensureStores() {
        if (regions == null) {
            if (server.world == null || server.world.worldEntity == null) {
                return false;
            }
            File root = new File(GlobalData.appDataPath(), "teammapsync/" + server.world.worldEntity.getUniqueID());
            regions = new TeamRegionStore(root);
            markers = new TeamMarkerStore(regions);
        }
        return true;
    }

    private static final class Session {
        final ServerClient client;
        int team = -1;
        int generation;
        double receiveTokens = RECEIVE_BURST_BYTES;
        final ArrayDeque<Packet> live = new ArrayDeque<>();
        /** Filled by the worker, drained by the server thread. */
        final ConcurrentLinkedQueue<Queued> bulk = new ConcurrentLinkedQueue<>();

        Session(ServerClient client) {
            this.client = client;
        }
    }

    /** A backlog packet tagged with the session it was produced for, so a team change discards stale ones. */
    private static final class Queued {
        final int generation;
        final PacketTeamMapTiles packet;

        Queued(int generation, PacketTeamMapTiles packet) {
            this.generation = generation;
            this.packet = packet;
        }
    }
}

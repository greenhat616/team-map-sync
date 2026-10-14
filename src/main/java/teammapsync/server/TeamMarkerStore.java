package teammapsync.server;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executor;
import necesse.engine.GameLog;
import teammapsync.TeamMarker;
import teammapsync.store.RegionStore;

/**
 * Every team's shared markers. Lives on the server thread; a team's file is read once on first use (it holds a
 * few hundred small records at most) and writes are handed to the worker as snapshots. A failed write puts the
 * team back on the dirty list.
 *
 * <p>Every change gets the next revision number, which clients use to ignore stale or repeated updates. Revisions
 * only need to grow within one server run: clients drop what they know at every new session.
 */
final class TeamMarkerStore {

    static final int MAX_MARKERS_PER_TEAM = 500;
    private static final int FILE_MAGIC = 0x544D534D; // "TMSM"
    private static final int FILE_VERSION = 1;

    private final RegionStore regions;
    private final HashMap<String, LinkedHashMap<UUID, Stored>> teams = new HashMap<>();
    private final HashSet<String> dirtyTeams = new HashSet<>();
    private final ConcurrentLinkedQueue<String> failedTeams = new ConcurrentLinkedQueue<>();
    private long revision;

    TeamMarkerStore(RegionStore regions) {
        this.regions = regions;
    }

    Collection<Stored> markers(String team) {
        return team(team).values();
    }

    /** @return the stored marker, or null if it was rejected */
    Stored set(String team, TeamMarker marker) {
        LinkedHashMap<UUID, Stored> markers = team(team);
        Stored existing = markers.get(marker.id);
        TeamMarker value;
        if (existing != null) {
            value = existing.marker.withLook(marker.icon, marker.name);
        } else if (markers.size() < MAX_MARKERS_PER_TEAM) {
            value = marker;
        } else {
            return null;
        }
        Stored stored = new Stored(value, ++revision);
        markers.put(value.id, stored);
        dirtyTeams.add(team);
        return stored;
    }

    /** @return the removal's revision, or -1 if there was no such marker */
    long remove(String team, UUID id) {
        if (team(team).remove(id) == null) {
            return -1;
        }
        dirtyTeams.add(team);
        return ++revision;
    }

    void saveDirty(Executor worker) {
        String failed;
        while ((failed = failedTeams.poll()) != null) {
            dirtyTeams.add(failed);
        }
        for (String team : dirtyTeams) {
            File file = file(team);
            ArrayList<TeamMarker> snapshot = new ArrayList<>();
            for (Stored stored : team(team).values()) {
                snapshot.add(stored.marker);
            }
            worker.execute(() -> {
                if (!write(file, snapshot)) {
                    failedTeams.add(team);
                }
            });
        }
        dirtyTeams.clear();
    }

    private LinkedHashMap<UUID, Stored> team(String team) {
        return teams.computeIfAbsent(team, this::read);
    }

    private File file(String team) {
        return new File(regions.bucketFolder(team), "markers.dat");
    }

    private LinkedHashMap<UUID, Stored> read(String team) {
        LinkedHashMap<UUID, Stored> markers = new LinkedHashMap<>();
        File file = file(team);
        if (!file.isFile()) {
            return markers;
        }
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(Files.readAllBytes(file.toPath())))) {
            if (in.readInt() != FILE_MAGIC || in.readInt() != FILE_VERSION) {
                return markers;
            }
            int count = Math.min(in.readInt(), MAX_MARKERS_PER_TEAM);
            for (int i = 0; i < count; i++) {
                TeamMarker marker = new TeamMarker(new UUID(in.readLong(), in.readLong()), in.readUTF(), in.readUTF(), in.readUTF(),
                        in.readInt(), in.readInt());
                if (marker.isValid()) {
                    markers.put(marker.id, new Stored(marker, ++revision));
                }
            }
        } catch (IOException e) {
            GameLog.warn.println("TeamMapSync: could not read " + file + ": " + e.getMessage());
        }
        return markers;
    }

    private static boolean write(File file, ArrayList<TeamMarker> markers) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);
            out.writeInt(FILE_MAGIC);
            out.writeInt(FILE_VERSION);
            out.writeInt(markers.size());
            for (TeamMarker marker : markers) {
                out.writeLong(marker.id.getMostSignificantBits());
                out.writeLong(marker.id.getLeastSignificantBits());
                out.writeUTF(marker.icon);
                out.writeUTF(marker.name);
                out.writeUTF(marker.level);
                out.writeInt(marker.tileX);
                out.writeInt(marker.tileY);
            }
            out.flush();
            RegionStore.writeAtomically(file, bytes.toByteArray());
            return true;
        } catch (IOException e) {
            GameLog.warn.println("TeamMapSync: could not save " + file + ", will retry: " + e.getMessage());
            return false;
        }
    }

    static final class Stored {
        final TeamMarker marker;
        final long revision;

        Stored(TeamMarker marker, long revision) {
            this.marker = marker;
            this.revision = revision;
        }
    }
}

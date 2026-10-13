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
import java.util.concurrent.Executor;
import necesse.engine.GameLog;
import teammapsync.TeamMarker;

/**
 * Every team's shared markers. Lives on the server thread; a team's file is read once on first use (it holds a
 * few hundred small records at most) and writes are handed to the worker as snapshots.
 */
final class TeamMarkerStore {

    static final int MAX_MARKERS_PER_TEAM = 500;
    private static final int FILE_MAGIC = 0x544D534D; // "TMSM"
    private static final int FILE_VERSION = 1;

    private final TeamRegionStore regions;
    private final HashMap<Integer, LinkedHashMap<UUID, TeamMarker>> teams = new HashMap<>();
    private final HashSet<Integer> dirtyTeams = new HashSet<>();

    TeamMarkerStore(TeamRegionStore regions) {
        this.regions = regions;
    }

    Collection<TeamMarker> markers(int team) {
        return team(team).values();
    }

    /** @return the stored marker, or null if it was rejected */
    TeamMarker set(int team, TeamMarker marker) {
        LinkedHashMap<UUID, TeamMarker> markers = team(team);
        TeamMarker existing = markers.get(marker.id);
        TeamMarker stored;
        if (existing != null) {
            stored = existing.withLook(marker.icon, marker.name);
        } else if (markers.size() < MAX_MARKERS_PER_TEAM) {
            stored = marker;
        } else {
            return null;
        }
        markers.put(stored.id, stored);
        dirtyTeams.add(team);
        return stored;
    }

    boolean remove(int team, UUID id) {
        if (team(team).remove(id) != null) {
            dirtyTeams.add(team);
            return true;
        }
        return false;
    }

    void saveDirty(Executor worker) {
        for (Integer team : dirtyTeams) {
            File file = file(team);
            ArrayList<TeamMarker> snapshot = new ArrayList<>(team(team).values());
            worker.execute(() -> write(file, snapshot));
        }
        dirtyTeams.clear();
    }

    private LinkedHashMap<UUID, TeamMarker> team(int team) {
        return teams.computeIfAbsent(team, this::read);
    }

    private File file(int team) {
        return new File(regions.teamFolder(team), "markers.dat");
    }

    private LinkedHashMap<UUID, TeamMarker> read(int team) {
        LinkedHashMap<UUID, TeamMarker> markers = new LinkedHashMap<>();
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
                    markers.put(marker.id, marker);
                }
            }
        } catch (IOException e) {
            GameLog.warn.println("TeamMapSync: could not read " + file + ": " + e.getMessage());
        }
        return markers;
    }

    private static void write(File file, ArrayList<TeamMarker> markers) {
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
            TeamRegionStore.writeAtomically(file, bytes.toByteArray());
        } catch (IOException e) {
            GameLog.warn.println("TeamMapSync: could not save " + file + ": " + e.getMessage());
        }
    }
}

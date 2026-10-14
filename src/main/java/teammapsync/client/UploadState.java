package teammapsync.client;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import necesse.engine.GameLog;
import teammapsync.store.RegionStore;

/**
 * Which local map files the server has acknowledged for one team: per region file, the modification time and
 * size it had when it was read for upload. A file is uploaded again when either differs, and only recorded once
 * every packet of it was acknowledged, so a lost packet or an unreadable file is retried on the next join.
 * Client worker thread only.
 */
final class UploadState {

    private final File file;
    private final HashMap<String, long[]> regions = new HashMap<>();
    private boolean dirty;

    private UploadState(File file) {
        this.file = file;
    }

    static UploadState load(File file) {
        UploadState state = new UploadState(file);
        if (!file.isFile()) {
            return state;
        }
        try {
            List<String> lines = Files.readAllLines(file.toPath(), StandardCharsets.UTF_8);
            for (String line : lines) {
                String[] parts = line.split(" ");
                if (parts.length == 3) {
                    state.regions.put(parts[0], new long[]{Long.parseLong(parts[1]), Long.parseLong(parts[2])});
                }
            }
        } catch (IOException | NumberFormatException e) {
            GameLog.warn.println("TeamMapSync: could not read " + file + ", uploading everything again: " + e.getMessage());
            state.regions.clear();
        }
        return state;
    }

    boolean isCurrent(String region, long modified, long size) {
        long[] known = regions.get(region);
        return known != null && known[0] == modified && known[1] == size;
    }

    void record(String region, long modified, long size) {
        regions.put(region, new long[]{modified, size});
        dirty = true;
    }

    void save() {
        if (!dirty) {
            return;
        }
        StringBuilder out = new StringBuilder(regions.size() * 40);
        for (Map.Entry<String, long[]> entry : regions.entrySet()) {
            out.append(entry.getKey()).append(' ').append(entry.getValue()[0]).append(' ').append(entry.getValue()[1]).append('\n');
        }
        try {
            RegionStore.writeAtomically(file, out.toString().getBytes(StandardCharsets.UTF_8));
            dirty = false;
        } catch (IOException e) {
            GameLog.warn.println("TeamMapSync: could not save " + file + ", will retry: " + e.getMessage());
        }
    }
}

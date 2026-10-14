package teammapsync.server;

import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;
import necesse.engine.network.server.Server;
import necesse.engine.save.LoadData;
import necesse.engine.save.SaveData;
import necesse.engine.team.PlayerTeam;

/**
 * A persistent id per vanilla team, stored inside the team's own save record.
 *
 * <p>Vanilla team IDs are not stable: the team save holds a {@code teamID}, but loading ignores it and numbers the
 * loaded teams from zero, so after a team disbands and the server restarts, another team takes its number. Stores
 * keyed by the number would hand one team's map and markers to another. Teams created before this mod (or before
 * the world was saved) get a fresh id on first use.
 */
public final class TeamKeys {

    private static final String SAVE_NAME = "teamMapSyncID";
    /** PlayerTeam does not override equals, so this is effectively an identity map. */
    private static final Map<PlayerTeam, String> keys = new WeakHashMap<>();

    private TeamKeys() {
    }

    public static synchronized String key(PlayerTeam team) {
        return keys.computeIfAbsent(team, t -> UUID.randomUUID().toString());
    }

    /** The persistent id of a vanilla team number, or null for no team. */
    static String key(Server server, int teamID) {
        if (teamID == -1 || server.world == null) {
            return null;
        }
        PlayerTeam team = server.world.getTeams().getTeam(teamID);
        return team == null ? null : key(team);
    }

    public static void onSave(PlayerTeam team, SaveData save) {
        save.addSafeString(SAVE_NAME, key(team));
    }

    public static synchronized void onLoad(PlayerTeam team, LoadData load) {
        String value = load.getSafeString(SAVE_NAME, null, false);
        if (value == null) {
            return;
        }
        try {
            keys.put(team, UUID.fromString(value).toString());
        } catch (IllegalArgumentException ignored) {
            // Not ours or damaged; a fresh id is assigned on first use.
        }
    }
}

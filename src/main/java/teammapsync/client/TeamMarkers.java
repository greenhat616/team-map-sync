package teammapsync.client;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;
import necesse.engine.localization.message.StaticMessage;
import necesse.engine.network.client.Client;
import necesse.engine.network.client.ClientLevelManager;
import necesse.engine.registries.MapIconRegistry;
import necesse.engine.util.LevelIdentifier;
import necesse.level.maps.mapData.GameMapIcon;
import teammapsync.TeamMarker;
import teammapsync.net.PacketTeamMarker;

/**
 * Keeps the team's shared markers inside the vanilla marker list, so the map draws, edits and deletes them like any
 * other marker.
 *
 * <p>Vanilla edits markers by assigning their fields directly and has no change events, so local edits are found by
 * polling: a changed icon or name is sent once it has stayed the same for one poll (the name box changes on every
 * keystroke). Shared markers are taken out of the list while vanilla saves {@code icons.mapdata}, since the server
 * owns them. Game thread only.
 */
final class TeamMarkers {

    /** Remembered checkbox choice for new markers. */
    static boolean shareNewMarkers;

    private final Client client;
    private final HashMap<UUID, Tracked> byId = new HashMap<>();
    private final IdentityHashMap<ClientLevelManager.MapMarker, Tracked> byMarker = new IdentityHashMap<>();
    private ArrayList<ClientLevelManager.MapMarker> hidden;

    TeamMarkers(Client client) {
        this.client = client;
    }

    boolean isShared(ClientLevelManager.MapMarker marker) {
        return byMarker.containsKey(marker);
    }

    void share(ClientLevelManager.MapMarker marker) {
        if (byMarker.containsKey(marker)) {
            return;
        }
        Tracked tracked = new Tracked(UUID.randomUUID(), marker);
        tracked.icon = iconOf(marker);
        tracked.name = nameOf(marker);
        track(tracked);
        send(PacketTeamMarker.set(toTeamMarker(tracked)));
    }

    void unshare(ClientLevelManager.MapMarker marker) {
        Tracked tracked = byMarker.remove(marker);
        if (tracked != null) {
            byId.remove(tracked.id);
            send(PacketTeamMarker.remove(tracked.id));
        }
    }

    /** Drops the previous team's markers from the map. */
    void reset() {
        for (Tracked tracked : byId.values()) {
            client.levelManager.deleteMapIcon(tracked.marker);
        }
        byId.clear();
        byMarker.clear();
    }

    void onSet(TeamMarker marker) {
        if (!marker.isValid()) {
            return;
        }
        GameMapIcon icon = MapIconRegistry.getIcon(marker.icon);
        if (icon == null) {
            icon = MapIconRegistry.defaultIcon;
        }
        Tracked tracked = byId.get(marker.id);
        if (tracked == null) {
            ClientLevelManager.MapMarker created = client.levelManager.addMapMarker(icon, new StaticMessage(marker.name),
                    new LevelIdentifier(marker.level), marker.tileX, marker.tileY);
            tracked = new Tracked(marker.id, created);
            track(tracked);
        } else {
            tracked.marker.icon = icon;
            tracked.marker.name = new StaticMessage(marker.name);
        }
        tracked.icon = iconOf(tracked.marker);
        tracked.name = nameOf(tracked.marker);
        tracked.pendingIcon = null;
        tracked.pendingName = null;
    }

    void onRemove(UUID id) {
        Tracked tracked = byId.remove(id);
        if (tracked != null) {
            byMarker.remove(tracked.marker);
            client.levelManager.deleteMapIcon(tracked.marker);
        }
    }

    void poll() {
        if (byId.isEmpty() || hidden != null) {
            return;
        }
        IdentityHashMap<ClientLevelManager.MapMarker, Boolean> present = new IdentityHashMap<>();
        for (ClientLevelManager.MapMarker marker : client.levelManager.getMapMarkers()) {
            present.put(marker, Boolean.TRUE);
        }
        if (present.isEmpty() && byId.size() > 1) {
            // The whole list was wiped locally (the deletemapdata command); that is not the team deleting markers.
            byId.clear();
            byMarker.clear();
            return;
        }
        Iterator<Tracked> it = byId.values().iterator();
        while (it.hasNext()) {
            Tracked tracked = it.next();
            if (!present.containsKey(tracked.marker)) {
                it.remove();
                byMarker.remove(tracked.marker);
                send(PacketTeamMarker.remove(tracked.id));
                continue;
            }
            String icon = iconOf(tracked.marker);
            String name = nameOf(tracked.marker);
            if (icon.equals(tracked.icon) && name.equals(tracked.name)) {
                tracked.pendingIcon = null;
                tracked.pendingName = null;
            } else if (icon.equals(tracked.pendingIcon) && name.equals(tracked.pendingName)) {
                tracked.icon = icon;
                tracked.name = name;
                tracked.pendingIcon = null;
                tracked.pendingName = null;
                send(PacketTeamMarker.set(toTeamMarker(tracked)));
            } else {
                tracked.pendingIcon = icon;
                tracked.pendingName = name;
            }
        }
    }

    /** Called before vanilla writes {@code icons.mapdata}: shared markers must not become personal ones. */
    void hideForSave() {
        if (hidden != null || byMarker.isEmpty()) {
            return;
        }
        Iterable<ClientLevelManager.MapMarker> markers = client.levelManager.getMapMarkers();
        if (!(markers instanceof List)) {
            return;
        }
        hidden = new ArrayList<>();
        Iterator<ClientLevelManager.MapMarker> it = markers.iterator();
        while (it.hasNext()) {
            ClientLevelManager.MapMarker marker = it.next();
            if (byMarker.containsKey(marker)) {
                hidden.add(marker);
                it.remove();
            }
        }
    }

    void restoreAfterSave() {
        if (hidden == null) {
            return;
        }
        Iterable<ClientLevelManager.MapMarker> markers = client.levelManager.getMapMarkers();
        if (markers instanceof List) {
            ((List<ClientLevelManager.MapMarker>) markers).addAll(hidden);
        }
        hidden = null;
    }

    private void track(Tracked tracked) {
        byId.put(tracked.id, tracked);
        byMarker.put(tracked.marker, tracked);
    }

    private void send(PacketTeamMarker packet) {
        client.network.sendPacket(packet);
    }

    private static TeamMarker toTeamMarker(Tracked tracked) {
        ClientLevelManager.MapMarker marker = tracked.marker;
        return new TeamMarker(tracked.id, tracked.icon, tracked.name, marker.levelIdentifier.stringID, marker.tileX, marker.tileY);
    }

    private static String iconOf(ClientLevelManager.MapMarker marker) {
        return marker.icon == null ? MapIconRegistry.defaultIcon.getStringID() : marker.icon.getStringID();
    }

    private static String nameOf(ClientLevelManager.MapMarker marker) {
        String name = marker.name == null ? "" : marker.name.translate();
        return name.length() > TeamMarker.MAX_NAME_LENGTH ? name.substring(0, TeamMarker.MAX_NAME_LENGTH) : name;
    }

    private static final class Tracked {
        final UUID id;
        final ClientLevelManager.MapMarker marker;
        String icon;
        String name;
        String pendingIcon;
        String pendingName;

        Tracked(UUID id, ClientLevelManager.MapMarker marker) {
            this.id = id;
            this.marker = marker;
        }
    }
}

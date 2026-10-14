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
import necesse.gfx.forms.presets.EditMapMarkerForm;
import necesse.level.maps.mapData.GameMapIcon;
import teammapsync.TeamMarker;
import teammapsync.net.PacketTeamMarker;

/**
 * Keeps the team's shared markers inside the vanilla marker list, so the map draws, edits and deletes them like any
 * other marker.
 *
 * <p>Vanilla edits markers by assigning their fields directly and has no change events, so local changes are found
 * by polling, and only once the marker's edit form is closed: a new marker that is cancelled never reaches the
 * team, and a rename is sent once, in its final form.
 *
 * <p>The server owns shared markers. Changes are sent as requests, at most one in flight per marker (a newer one
 * waits and replaces older waiting ones), and the server's result comes back to everyone, in order (marker packets
 * travel in the ordered stream). A result for a marker the player is editing, or has a request in flight for, is
 * held back until that is over, so it cannot overwrite an edit that was not sent yet; it is then applied unless the
 * player changed the marker. Shared markers are taken out of the list while vanilla saves {@code icons.mapdata}.
 * Game thread only.
 */
final class TeamMarkers {

    /** Remembered checkbox choice for new markers. */
    static boolean shareNewMarkers;

    interface Sender {
        /** Sends a marker request reliably; {@code onAcked} runs once the server has processed it. */
        void send(int op, UUID id, TeamMarker marker, Runnable onAcked);
    }

    private final Client client;
    private final Sender sender;
    private final HashMap<UUID, Tracked> byId = new HashMap<>();
    private final IdentityHashMap<ClientLevelManager.MapMarker, Tracked> byMarker = new IdentityHashMap<>();
    private final HashMap<UUID, Request> requests = new HashMap<>();
    private ArrayList<ClientLevelManager.MapMarker> hidden;

    TeamMarkers(Client client, Sender sender) {
        this.client = client;
        this.sender = sender;
    }

    boolean isShared(ClientLevelManager.MapMarker marker) {
        Tracked tracked = byMarker.get(marker);
        return tracked != null && tracked.shareIntent;
    }

    /** The marker's edit form opened; its changes are sent once the form is closed. */
    void editing(ClientLevelManager.MapMarker marker, EditMapMarkerForm form) {
        Tracked tracked = byMarker.get(marker);
        if (tracked != null) {
            tracked.form = form;
        }
    }

    void setShared(ClientLevelManager.MapMarker marker, boolean shared, EditMapMarkerForm form) {
        Tracked tracked = byMarker.get(marker);
        if (tracked == null) {
            if (!shared) {
                return;
            }
            tracked = new Tracked(UUID.randomUUID(), marker);
            track(tracked);
        }
        tracked.shareIntent = shared;
        tracked.form = form;
    }

    /** Drops the previous session's markers from the map. */
    void reset() {
        for (Tracked tracked : byId.values()) {
            client.levelManager.deleteMapIcon(tracked.marker);
        }
        forget();
    }

    /** Forgets shared markers without deleting them for the team (vanilla's map data was wiped locally). */
    void forget() {
        byId.clear();
        byMarker.clear();
        requests.clear();
    }

    void onSet(TeamMarker marker) {
        if (!marker.isValid()) {
            return;
        }
        Tracked tracked = byId.get(marker.id);
        if (tracked == null) {
            ClientLevelManager.MapMarker created = client.levelManager.addMapMarker(icon(marker), new StaticMessage(marker.name),
                    new LevelIdentifier(marker.level), marker.tileX, marker.tileY);
            tracked = new Tracked(marker.id, created);
            tracked.sent = true;
            track(tracked);
            tracked.icon = iconOf(created);
            tracked.name = nameOf(created);
        } else if (tracked.form != null || requests.containsKey(marker.id)) {
            tracked.deferred = marker;
        } else {
            tracked.deferred = null; // superseded: this update is newer
            applyLook(tracked, marker);
        }
    }

    void onRemove(UUID id) {
        Tracked tracked = byId.remove(id);
        if (tracked != null) {
            byMarker.remove(tracked.marker);
            client.levelManager.deleteMapIcon(tracked.marker);
        }
    }

    /** The team is at its marker limit: the marker stays, as a personal one. */
    void onReject(UUID id) {
        Tracked tracked = byId.remove(id);
        if (tracked != null) {
            byMarker.remove(tracked.marker);
        }
    }

    void poll() {
        if (hidden != null || byId.isEmpty()) {
            return;
        }
        IdentityHashMap<ClientLevelManager.MapMarker, Boolean> present = new IdentityHashMap<>();
        for (ClientLevelManager.MapMarker marker : client.levelManager.getMapMarkers()) {
            present.put(marker, Boolean.TRUE);
        }
        Iterator<Tracked> it = byId.values().iterator();
        while (it.hasNext()) {
            Tracked tracked = it.next();
            if (tracked.form != null) {
                if (!tracked.form.isContinued() && !tracked.form.isDisposed()) {
                    continue;
                }
                tracked.form = null;
            }
            if (tracked.deferred != null && !requests.containsKey(tracked.id)) {
                TeamMarker deferred = tracked.deferred;
                tracked.deferred = null;
                if (present.containsKey(tracked.marker) && tracked.shareIntent
                        && iconOf(tracked.marker).equals(tracked.icon) && nameOf(tracked.marker).equals(tracked.name)) {
                    applyLook(tracked, deferred); // not changed locally: take the team's newer version
                    continue;
                }
            }
            if (!present.containsKey(tracked.marker) || !tracked.shareIntent) {
                it.remove();
                byMarker.remove(tracked.marker);
                if (tracked.sent) {
                    request(tracked.id, PacketTeamMarker.OP_REMOVE, null);
                }
                continue;
            }
            String icon = iconOf(tracked.marker);
            String name = nameOf(tracked.marker);
            if (!tracked.sent || !icon.equals(tracked.icon) || !name.equals(tracked.name)) {
                tracked.icon = icon;
                tracked.name = name;
                tracked.sent = true;
                ClientLevelManager.MapMarker marker = tracked.marker;
                request(tracked.id, PacketTeamMarker.OP_SET,
                        new TeamMarker(tracked.id, icon, name, marker.levelIdentifier.stringID, marker.tileX, marker.tileY));
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
            Tracked tracked = byMarker.get(marker);
            // Not yet accepted by the server: still the player's own, so it is saved.
            if (tracked != null && tracked.sent) {
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

    private void applyLook(Tracked tracked, TeamMarker marker) {
        tracked.marker.icon = icon(marker);
        tracked.marker.name = new StaticMessage(marker.name);
        tracked.sent = true;
        tracked.icon = iconOf(tracked.marker);
        tracked.name = nameOf(tracked.marker);
    }

    private static GameMapIcon icon(TeamMarker marker) {
        GameMapIcon icon = MapIconRegistry.getIcon(marker.icon);
        return icon == null ? MapIconRegistry.defaultIcon : icon;
    }

    private void request(UUID id, int op, TeamMarker marker) {
        Request request = requests.computeIfAbsent(id, k -> new Request());
        if (request.inFlight) {
            request.waitingOp = op;
            request.waitingMarker = marker;
            return;
        }
        dispatch(id, request, op, marker);
    }

    private void dispatch(UUID id, Request request, int op, TeamMarker marker) {
        request.inFlight = true;
        sender.send(op, id, marker, () -> onProcessed(id, request));
    }

    private void onProcessed(UUID id, Request request) {
        if (requests.get(id) != request) {
            return; // the session was reset meanwhile
        }
        request.inFlight = false;
        if (request.waitingOp >= 0) {
            int op = request.waitingOp;
            TeamMarker marker = request.waitingMarker;
            request.waitingOp = -1;
            request.waitingMarker = null;
            dispatch(id, request, op, marker);
        } else {
            requests.remove(id);
        }
    }

    private void track(Tracked tracked) {
        byId.put(tracked.id, tracked);
        byMarker.put(tracked.marker, tracked);
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
        /** The checkbox state; applied when the edit form closes. */
        boolean shareIntent = true;
        /** The server has (or was asked to have) this marker. */
        boolean sent;
        /** Look last sent or received. */
        String icon;
        String name;
        EditMapMarkerForm form;
        /** A team update held back while the player edits this marker or has a request for it in flight. */
        TeamMarker deferred;

        Tracked(UUID id, ClientLevelManager.MapMarker marker) {
            this.id = id;
            this.marker = marker;
        }
    }

    private static final class Request {
        boolean inFlight;
        int waitingOp = -1;
        TeamMarker waitingMarker;
    }
}

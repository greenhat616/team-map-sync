package teammapsync;

import necesse.engine.GameEventListener;
import necesse.engine.GameEvents;
import necesse.engine.events.ServerClientDisconnectEvent;
import necesse.engine.events.ServerStartEvent;
import necesse.engine.events.ServerStopEvent;
import necesse.engine.modLoader.annotations.ModEntry;
import necesse.engine.registries.PacketRegistry;
import teammapsync.net.PacketTeamMapAck;
import teammapsync.net.PacketTeamMapSession;
import teammapsync.net.PacketTeamMapTiles;
import teammapsync.net.PacketTeamMarker;
import teammapsync.server.TeamMapServer;

/**
 * Shares map exploration and opted-in map markers between players on the same team.
 *
 * <p>Vanilla keeps the discovered map only on each client and shares it solely through the cartographer's table.
 * This mod records the tiles a player's own discovery writes, relays them (still compressed) through the server to
 * online teammates, and keeps a merged copy per team on the server for teammates who were offline.
 */
@ModEntry
public class TeamMapSyncMod {

    public void init() {
        PacketRegistry.registerPacket(PacketTeamMapAck.class);
        PacketRegistry.registerPacket(PacketTeamMapSession.class);
        PacketRegistry.registerPacket(PacketTeamMapTiles.class);
        PacketRegistry.registerPacket(PacketTeamMarker.class);

        GameEvents.addListener(ServerStartEvent.class, new GameEventListener<ServerStartEvent>() {
            @Override
            public void onEvent(ServerStartEvent event) {
                TeamMapServer.start(event.server);
            }
        });

        GameEvents.addListener(ServerClientDisconnectEvent.class, new GameEventListener<ServerClientDisconnectEvent>() {
            @Override
            public void onEvent(ServerClientDisconnectEvent event) {
                TeamMapServer.onDisconnect(event.client);
            }
        });

        GameEvents.addListener(ServerStopEvent.class, new GameEventListener<ServerStopEvent>() {
            @Override
            public void onEvent(ServerStopEvent event) {
                TeamMapServer.stop(event.server);
            }
        });
    }
}

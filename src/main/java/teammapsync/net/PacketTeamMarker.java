package teammapsync.net;

import java.util.UUID;
import necesse.engine.network.NetworkPacket;
import necesse.engine.network.Packet;
import necesse.engine.network.PacketReader;
import necesse.engine.network.PacketWriter;
import necesse.engine.network.client.Client;
import necesse.engine.network.server.Server;
import necesse.engine.network.server.ServerClient;
import teammapsync.TeamMarker;
import teammapsync.client.TeamMapClient;
import teammapsync.server.TeamMapServer;

/** Adds, updates or removes one team map marker, in both directions. */
public class PacketTeamMarker extends Packet {

    public final UUID id;
    /** Null when the marker is removed. */
    public final TeamMarker marker;

    public PacketTeamMarker(byte[] data) {
        super(data);
        PacketReader reader = new PacketReader(this);
        this.id = new UUID(reader.getNextLong(), reader.getNextLong());
        if (reader.getNextBoolean()) {
            String icon = reader.getNextString();
            String name = reader.getNextString();
            String level = reader.getNextString();
            int tileX = reader.getNextInt();
            int tileY = reader.getNextInt();
            this.marker = new TeamMarker(this.id, icon, name, level, tileX, tileY);
        } else {
            this.marker = null;
        }
    }

    private PacketTeamMarker(UUID id, TeamMarker marker) {
        this.id = id;
        this.marker = marker;
        PacketWriter writer = new PacketWriter(this);
        writer.putNextLong(id.getMostSignificantBits());
        writer.putNextLong(id.getLeastSignificantBits());
        writer.putNextBoolean(marker != null);
        if (marker != null) {
            writer.putNextString(marker.icon);
            writer.putNextString(marker.name);
            writer.putNextString(marker.level);
            writer.putNextInt(marker.tileX);
            writer.putNextInt(marker.tileY);
        }
    }

    public static PacketTeamMarker set(TeamMarker marker) {
        return new PacketTeamMarker(marker.id, marker);
    }

    public static PacketTeamMarker remove(UUID id) {
        return new PacketTeamMarker(id, null);
    }

    @Override
    public void processServer(NetworkPacket packet, Server server, ServerClient client) {
        TeamMapServer.onMarker(server, client, this);
    }

    @Override
    public void processClient(NetworkPacket packet, Client client) {
        TeamMapClient.onMarker(client, this);
    }
}

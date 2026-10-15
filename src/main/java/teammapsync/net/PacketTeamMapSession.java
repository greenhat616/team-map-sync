package teammapsync.net;

import necesse.engine.network.NetworkPacket;
import necesse.engine.network.Packet;
import necesse.engine.network.PacketReader;
import necesse.engine.network.PacketWriter;
import necesse.engine.network.client.Client;
import teammapsync.client.TeamMapClient;

/**
 * Server to client: a sync session started. {@code epoch} grows with every session and is carried by every other
 * packet, so data from a previous team or connection is recognised and dropped. {@code teamKey} is the team's
 * persistent id, or null when the player has no team. {@code lowTraffic} asks the client to upload masks instead of
 * colours ({@link PacketTeamMapTiles#MODE_MASK}). Repeated until the client's heartbeat names this epoch.
 */
public class PacketTeamMapSession extends Packet {

    public final int epoch;
    public final String teamKey;
    public final boolean lowTraffic;

    public PacketTeamMapSession(byte[] data) {
        super(data);
        PacketReader reader = new PacketReader(this);
        this.epoch = reader.getNextInt();
        this.teamKey = reader.getNextBoolean() ? reader.getNextString() : null;
        this.lowTraffic = reader.getNextBoolean();
    }

    public PacketTeamMapSession(int epoch, String teamKey, boolean lowTraffic) {
        this.epoch = epoch;
        this.teamKey = teamKey;
        this.lowTraffic = lowTraffic;
        PacketWriter writer = new PacketWriter(this);
        writer.putNextInt(epoch);
        writer.putNextBoolean(teamKey != null);
        if (teamKey != null) {
            writer.putNextString(teamKey);
        }
        writer.putNextBoolean(lowTraffic);
    }

    @Override
    public void processClient(NetworkPacket packet, Client client) {
        TeamMapClient.onSession(client, this.epoch, this.teamKey, this.lowTraffic);
    }
}

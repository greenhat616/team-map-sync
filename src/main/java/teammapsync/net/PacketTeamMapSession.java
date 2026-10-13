package teammapsync.net;

import necesse.engine.network.NetworkPacket;
import necesse.engine.network.Packet;
import necesse.engine.network.PacketReader;
import necesse.engine.network.PacketWriter;
import necesse.engine.network.client.Client;
import teammapsync.client.TeamMapClient;

/**
 * Server to client: a sync session started for this team (-1 means none). Sent after the hello and whenever the
 * player's team changes; the client drops the previous team's markers and starts sharing with the new one.
 */
public class PacketTeamMapSession extends Packet {

    public final int teamID;

    public PacketTeamMapSession(byte[] data) {
        super(data);
        this.teamID = new PacketReader(this).getNextInt();
    }

    public PacketTeamMapSession(int teamID) {
        this.teamID = teamID;
        new PacketWriter(this).putNextInt(teamID);
    }

    @Override
    public void processClient(NetworkPacket packet, Client client) {
        TeamMapClient.onSession(client, this.teamID);
    }
}

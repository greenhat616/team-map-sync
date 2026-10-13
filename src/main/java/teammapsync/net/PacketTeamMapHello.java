package teammapsync.net;

import necesse.engine.network.NetworkPacket;
import necesse.engine.network.Packet;
import necesse.engine.network.server.Server;
import necesse.engine.network.server.ServerClient;
import teammapsync.server.TeamMapServer;

/** Client to server: the client has loaded in and its map can receive data. Starts a sync session. */
public class PacketTeamMapHello extends Packet {

    public PacketTeamMapHello(byte[] data) {
        super(data);
    }

    public PacketTeamMapHello() {
    }

    @Override
    public void processServer(NetworkPacket packet, Server server, ServerClient client) {
        TeamMapServer.onHello(server, client);
    }
}

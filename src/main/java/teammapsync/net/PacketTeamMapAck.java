package teammapsync.net;

import necesse.engine.network.NetworkPacket;
import necesse.engine.network.Packet;
import necesse.engine.network.PacketReader;
import necesse.engine.network.PacketWriter;
import necesse.engine.network.client.Client;
import necesse.engine.network.server.Server;
import necesse.engine.network.server.ServerClient;
import teammapsync.client.TeamMapClient;
import teammapsync.server.TeamMapServer;

/**
 * Acknowledges received {@link ReliableChannel} packets, in both directions.
 *
 * <p>From the client it is also the heartbeat: it is sent at least once a second and names the session the client
 * knows. The server starts a session on the first one and repeats its session announcement until the client
 * reports it, so a lost announcement only delays syncing. {@code busy} tells the server the client's vanilla
 * region loading is behind, so bulk map data backs off.
 */
public class PacketTeamMapAck extends Packet {

    public static final int MAX_SEQS = 256;

    public final int epoch;
    public final boolean busy;
    public final int[] seqs;

    public PacketTeamMapAck(byte[] data) {
        super(data);
        PacketReader reader = new PacketReader(this);
        this.epoch = reader.getNextInt();
        this.busy = reader.getNextBoolean();
        int count = reader.getNextInt();
        this.seqs = new int[Math.max(0, Math.min(count, MAX_SEQS))];
        for (int i = 0; i < seqs.length; i++) {
            seqs[i] = reader.getNextInt();
        }
    }

    public PacketTeamMapAck(int epoch, boolean busy, int[] seqs) {
        this.epoch = epoch;
        this.busy = busy;
        this.seqs = seqs;
        PacketWriter writer = new PacketWriter(this);
        writer.putNextInt(epoch);
        writer.putNextBoolean(busy);
        writer.putNextInt(seqs.length);
        for (int seq : seqs) {
            writer.putNextInt(seq);
        }
    }

    @Override
    public void processServer(NetworkPacket packet, Server server, ServerClient client) {
        TeamMapServer.onStatus(server, client, this);
    }

    @Override
    public void processClient(NetworkPacket packet, Client client) {
        TeamMapClient.onAck(client, this);
    }
}

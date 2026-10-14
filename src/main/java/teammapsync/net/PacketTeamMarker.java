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

/**
 * One marker change, in both directions, sent through a {@link ReliableChannel}.
 *
 * <p>Clients send {@link #OP_SET} and {@link #OP_REMOVE} requests. The server applies them and sends the result to
 * every teammate, the sender included, tagged with a revision that grows with every change of the team's markers;
 * clients ignore anything older than what they already applied for that marker. {@link #OP_REJECT} tells the
 * sender a new marker was not accepted (the team is at its marker limit).
 */
public class PacketTeamMarker extends Packet {

    public static final int OP_SET = 0;
    public static final int OP_REMOVE = 1;
    public static final int OP_REJECT = 2;
    /** Upper bound of the encoded size, for rate limiting. */
    public static final int MAX_SIZE = 320;

    public final int epoch;
    public final int seq;
    /** Position in the sender's ordered stream, or 0 for bulk data (see {@link ReliableChannel}). */
    public final int ordinal;
    public final int op;
    public final long revision;
    public final UUID id;
    /** Set for {@link #OP_SET} only. */
    public final TeamMarker marker;

    public PacketTeamMarker(byte[] data) {
        super(data);
        PacketReader reader = new PacketReader(this);
        this.epoch = reader.getNextInt();
        this.seq = reader.getNextInt();
        this.ordinal = reader.getNextInt();
        this.op = reader.getNextByteUnsigned();
        this.revision = reader.getNextLong();
        this.id = new UUID(reader.getNextLong(), reader.getNextLong());
        if (op == OP_SET) {
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

    public PacketTeamMarker(int epoch, int seq, int ordinal, int op, long revision, UUID id, TeamMarker marker) {
        this.epoch = epoch;
        this.seq = seq;
        this.ordinal = ordinal;
        this.op = op;
        this.revision = revision;
        this.id = id;
        this.marker = marker;
        PacketWriter writer = new PacketWriter(this);
        writer.putNextInt(epoch);
        writer.putNextInt(seq);
        writer.putNextInt(ordinal);
        writer.putNextByteUnsigned(op);
        writer.putNextLong(revision);
        writer.putNextLong(id.getMostSignificantBits());
        writer.putNextLong(id.getLeastSignificantBits());
        if (op == OP_SET) {
            writer.putNextString(marker.icon);
            writer.putNextString(marker.name);
            writer.putNextString(marker.level);
            writer.putNextInt(marker.tileX);
            writer.putNextInt(marker.tileY);
        }
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

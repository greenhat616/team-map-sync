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
 * Discovered tiles of one band of one map region, in both directions. The payload stays in
 * {@link teammapsync.codec.TileCodec} form end to end, so the server can relay it without decompressing. Sent
 * through a {@link ReliableChannel}; bands keep a packet to about one or two network fragments.
 */
public class PacketTeamMapTiles extends Packet {

    /** Fresh observation: replaces whatever colour the receiver has. */
    public static final int MODE_LIVE = 0;
    /** Older or stored data: only fills tiles the receiver has not discovered. */
    public static final int MODE_FILL = 1;
    /**
     * Low traffic mode, client to server only: the payload is a {@link teammapsync.codec.TileCodec#encodeMask mask}
     * of tiles the player's discovery changed; the server colours them from its own level and relays the result.
     */
    public static final int MODE_MASK = 2;

    /** A band of 16 full rows is about 12 KB before compression; this only rejects garbage. */
    public static final int MAX_PAYLOAD = 32 * 1024;

    public final int epoch;
    public final int seq;
    /** Position in the sender's ordered stream, or 0 for bulk data (see {@link ReliableChannel}). */
    public final int ordinal;
    public final int mode;
    public final String level;
    public final int regionX;
    public final int regionY;
    public final byte[] payload;

    public PacketTeamMapTiles(byte[] data) {
        super(data);
        PacketReader reader = new PacketReader(this);
        this.epoch = reader.getNextInt();
        this.seq = reader.getNextInt();
        this.ordinal = reader.getNextInt();
        this.mode = reader.getNextByteUnsigned();
        this.level = reader.getNextString();
        this.regionX = reader.getNextInt();
        this.regionY = reader.getNextInt();
        int length = reader.getNextInt();
        this.payload = length < 0 || length > MAX_PAYLOAD ? new byte[0] : reader.getNextBytes(length);
    }

    public PacketTeamMapTiles(int epoch, int seq, int ordinal, int mode, String level, int regionX, int regionY, byte[] payload) {
        this.epoch = epoch;
        this.seq = seq;
        this.ordinal = ordinal;
        this.mode = mode;
        this.level = level;
        this.regionX = regionX;
        this.regionY = regionY;
        this.payload = payload;
        PacketWriter writer = new PacketWriter(this);
        writer.putNextInt(epoch);
        writer.putNextInt(seq);
        writer.putNextInt(ordinal);
        writer.putNextByteUnsigned(mode);
        writer.putNextString(level);
        writer.putNextInt(regionX);
        writer.putNextInt(regionY);
        writer.putNextInt(payload.length);
        writer.putNextBytes(payload);
    }

    @Override
    public void processServer(NetworkPacket packet, Server server, ServerClient client) {
        TeamMapServer.onTiles(server, client, this);
    }

    @Override
    public void processClient(NetworkPacket packet, Client client) {
        TeamMapClient.onTiles(client, this);
    }
}

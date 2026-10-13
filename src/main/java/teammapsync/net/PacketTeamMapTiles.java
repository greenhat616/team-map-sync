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
 * Discovered tiles of one map region, in both directions. The payload stays in {@link teammapsync.codec.TileCodec}
 * form end to end, so the server can relay it without decompressing.
 */
public class PacketTeamMapTiles extends Packet {

    /** Fresh observation: replaces whatever colour the receiver has. */
    public static final int MODE_LIVE = 0;
    /** Older or stored data: only fills tiles the receiver has not discovered. */
    public static final int MODE_FILL = 1;

    public final int mode;
    public final String level;
    public final int regionX;
    public final int regionY;
    public final byte[] payload;

    public PacketTeamMapTiles(byte[] data) {
        super(data);
        PacketReader reader = new PacketReader(this);
        this.mode = reader.getNextByteUnsigned();
        this.level = reader.getNextString();
        this.regionX = reader.getNextInt();
        this.regionY = reader.getNextInt();
        int length = reader.getNextInt();
        this.payload = length < 0 || length > MAX_PAYLOAD ? new byte[0] : reader.getNextBytes(length);
    }

    public PacketTeamMapTiles(int mode, String level, int regionX, int regionY, byte[] payload) {
        this.mode = mode;
        this.level = level;
        this.regionX = regionX;
        this.regionY = regionY;
        this.payload = payload;
        PacketWriter writer = new PacketWriter(this);
        writer.putNextByteUnsigned(mode);
        writer.putNextString(level);
        writer.putNextInt(regionX);
        writer.putNextInt(regionY);
        writer.putNextInt(payload.length);
        writer.putNextBytes(payload);
    }

    /** A compressed full region is far below this; it only rejects garbage. */
    public static final int MAX_PAYLOAD = 512 * 1024;

    @Override
    public void processServer(NetworkPacket packet, Server server, ServerClient client) {
        TeamMapServer.onTiles(server, client, this);
    }

    @Override
    public void processClient(NetworkPacket packet, Client client) {
        TeamMapClient.onTiles(client, this);
    }
}

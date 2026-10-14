package teammapsync.codec;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/**
 * Wire and disk format for a set of discovered map tiles inside one 256x256 map region.
 *
 * <p>The tiles are written as a dense grid over their bounding box, three bytes (RGB) per tile row by row, with
 * {@code 0} for tiles not included, then deflated. Measured on real map regions this is about half the size of a
 * sparse index list: map colours repeat both along a row and between rows, and a grid keeps the vertical
 * neighbour at a fixed distance where deflate finds it. Pure black counts as "not included", which matches
 * vanilla, whose map files cannot store black either.
 *
 * <p>Deflate level 6 was kept over zstd after measuring both on real regions: zstd levels 5 to 9 give the same
 * size (levels 3 and 4 are 11-13% larger, their single-probe match finder misses the row-to-row repeats) and
 * encode faster, but that saving lands on a worker thread, while zstd needs a native library of several MB. The
 * first byte names the format so another compressor can still be added without breaking stored data.
 */
public final class TileCodec {

    public static final byte CODEC_GRID_DEFLATE = 1;
    public static final int MAX_TILES = 256 * 256;
    public static final int BAND_ROWS = 16;

    private static final ThreadLocal<Deflater> DEFLATER = ThreadLocal.withInitial(() -> new Deflater(6, true));
    private static final ThreadLocal<Inflater> INFLATER = ThreadLocal.withInitial(() -> new Inflater(true));

    private TileCodec() {
    }

    /**
     * @param indices tile indices ({@code x | y << 8}), each below {@link #MAX_TILES}, in any order
     * @param rgb     24-bit colours matching {@code indices}; 0 means the tile is left out
     */
    public static byte[] encode(int[] indices, int[] rgb, int count) {
        int minX = 255, minY = 255, maxX = -1, maxY = -1;
        for (int i = 0; i < count; i++) {
            if (rgb[i] == 0) {
                continue;
            }
            int x = indices[i] & 0xFF;
            int y = indices[i] >>> 8;
            minX = Math.min(minX, x);
            minY = Math.min(minY, y);
            maxX = Math.max(maxX, x);
            maxY = Math.max(maxY, y);
        }
        if (maxX < 0) {
            minX = minY = maxX = maxY = 0;
        }
        int width = maxX - minX + 1;
        int height = maxY - minY + 1;
        byte[] raw = new byte[4 + width * height * 3];
        raw[0] = (byte) minX;
        raw[1] = (byte) minY;
        raw[2] = (byte) (width - 1);
        raw[3] = (byte) (height - 1);
        for (int i = 0; i < count; i++) {
            if (rgb[i] == 0) {
                continue;
            }
            int pos = 4 + ((indices[i] & 0xFF) - minX + ((indices[i] >>> 8) - minY) * width) * 3;
            raw[pos] = (byte) (rgb[i] >> 16);
            raw[pos + 1] = (byte) (rgb[i] >> 8);
            raw[pos + 2] = (byte) rgb[i];
        }

        Deflater deflater = DEFLATER.get();
        deflater.reset();
        deflater.setInput(raw);
        deflater.finish();
        byte[] out = new byte[Math.max(256, raw.length / 4)];
        out[0] = CODEC_GRID_DEFLATE;
        int written = 1;
        while (!deflater.finished()) {
            if (written == out.length) {
                out = Arrays.copyOf(out, out.length * 2);
            }
            written += deflater.deflate(out, written, out.length - written);
        }
        return Arrays.copyOf(out, written);
    }

    /**
     * Encodes tiles sorted by index as one payload per band of {@link #BAND_ROWS} rows. Vanilla drops a whole
     * packet when one of its 1 KB network fragments is lost, so small packets lose (and resend) far less; a band of
     * a fully explored region compresses to about one fragment.
     */
    public static List<byte[]> encodeBands(int[] indices, int[] rgb, int count) {
        ArrayList<byte[]> out = new ArrayList<>();
        int start = 0;
        while (start < count) {
            int band = (indices[start] >>> 8) / BAND_ROWS;
            int end = start + 1;
            while (end < count && (indices[end] >>> 8) / BAND_ROWS == band) {
                end++;
            }
            boolean any = false;
            for (int i = start; i < end && !any; i++) {
                any = rgb[i] != 0;
            }
            if (any) {
                out.add(encode(Arrays.copyOfRange(indices, start, end), Arrays.copyOfRange(rgb, start, end), end - start));
            }
            start = end;
        }
        return out;
    }

    /** Returns the included tiles, sorted by index. */
    public static Tiles decode(byte[] payload) throws DataFormatException {
        if (payload.length < 2 || payload[0] != CODEC_GRID_DEFLATE) {
            throw new DataFormatException("Unknown tile codec");
        }
        Inflater inflater = INFLATER.get();
        inflater.reset();
        inflater.setInput(payload, 1, payload.length - 1);
        byte[] header = new byte[4];
        inflateFully(inflater, header);
        int minX = header[0] & 0xFF;
        int minY = header[1] & 0xFF;
        int width = (header[2] & 0xFF) + 1;
        int height = (header[3] & 0xFF) + 1;
        if (minX + width > 256 || minY + height > 256) {
            throw new DataFormatException("Tile grid outside the region");
        }
        byte[] grid = new byte[width * height * 3];
        inflateFully(inflater, grid);

        int count = 0;
        for (int i = 0; i < grid.length; i += 3) {
            if ((grid[i] | grid[i + 1] | grid[i + 2]) != 0) {
                count++;
            }
        }
        int[] indices = new int[count];
        int[] rgb = new int[count];
        int n = 0;
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int pos = (x + y * width) * 3;
                int color = (grid[pos] & 0xFF) << 16 | (grid[pos + 1] & 0xFF) << 8 | grid[pos + 2] & 0xFF;
                if (color != 0) {
                    indices[n] = (minX + x) | (minY + y) << 8;
                    rgb[n++] = color;
                }
            }
        }
        return new Tiles(indices, rgb);
    }

    private static void inflateFully(Inflater inflater, byte[] target) throws DataFormatException {
        int read = 0;
        while (read < target.length) {
            int n = inflater.inflate(target, read, target.length - read);
            if (n == 0 && (inflater.finished() || inflater.needsInput() || inflater.needsDictionary())) {
                throw new DataFormatException("Truncated tile data");
            }
            read += n;
        }
    }

    /** Decoded tiles: parallel arrays sorted by index. */
    public static final class Tiles {
        public final int[] indices;
        public final int[] rgb;

        public Tiles(int[] indices, int[] rgb) {
            this.indices = indices;
            this.rgb = rgb;
        }

        public int size() {
            return indices.length;
        }
    }
}

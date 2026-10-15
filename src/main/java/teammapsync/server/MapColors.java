package teammapsync.server;

import java.awt.Color;
import java.util.BitSet;
import necesse.engine.registries.TileRegistry;
import necesse.level.gameObject.GameObject;
import necesse.level.gameTile.GameTile;
import necesse.level.maps.Level;

/**
 * Map colours computed on the server for low traffic mode: vanilla's rules ({@code ClientDiscoveredMap.getMapColor})
 * without the lighting step, which needs the client's light map. Server thread only.
 *
 * <p>Some colour methods need client textures (trees pick a colour by sprite); on a dedicated server they throw,
 * and that tile or object type then uses its plain map colour from then on.
 */
final class MapColors {

    private static final BitSet brokenTiles = new BitSet();
    private static final BitSet brokenObjects = new BitSet();

    private MapColors() {
    }

    /** 24-bit colour of a tile, or 0 if it is not loaded on the server or has no map colour. */
    static int unlit(Level level, int tileX, int tileY) {
        if (!level.regionManager.isRegionLoadingCompleteByTile(tileX, tileY)) {
            return 0; // never load terrain for this: the discovering player is near, so it is normally loaded
        }
        GameTile tile = level.getTile(tileX, tileY);
        if (tile == null || tile.getID() == TileRegistry.emptyID) {
            return 0;
        }
        Color color = tileColor(tile, level, tileX, tileY);
        GameObject object = level.getObject(tileX, tileY);
        if (object != null && object.getID() != 0) {
            Color objectColor = objectColor(object, level, tileX, tileY);
            if (objectColor != null) {
                color = objectColor;
            }
        }
        if (color == null) {
            return 0;
        }
        int rgb = color.getRGB() & 0xFFFFFF;
        return rgb == 0 ? 0x010101 : rgb; // pure black means undiscovered in map data
    }

    private static Color tileColor(GameTile tile, Level level, int tileX, int tileY) {
        int id = tile.getID();
        if (!brokenTiles.get(id)) {
            try {
                return tile.getMapColor(level, tileX, tileY);
            } catch (RuntimeException e) {
                brokenTiles.set(id);
            }
        }
        return tile.mapColor;
    }

    private static Color objectColor(GameObject object, Level level, int tileX, int tileY) {
        int id = object.getID();
        if (!brokenObjects.get(id)) {
            try {
                return object.getMapColor(level, tileX, tileY);
            } catch (RuntimeException e) {
                brokenObjects.set(id);
            }
        }
        return object.mapColor;
    }
}

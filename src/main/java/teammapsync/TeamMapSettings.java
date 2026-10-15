package teammapsync;

import necesse.engine.modLoader.ModSettings;
import necesse.engine.save.LoadData;
import necesse.engine.save.SaveData;

/**
 * Saved by vanilla in {@code cfg/mods/a632079.teammapsync.cfg} of the game's or dedicated server's data folder.
 * Only the server's (or host's) value matters; it is read when the server starts.
 */
public class TeamMapSettings extends ModSettings {

    /**
     * Players upload which tiles they discovered instead of their colours, and the server colours them from its
     * own level without lighting. Uploads become very small and cave data much smaller, but teammates see caves
     * and buildings evenly lit, and a few tiles (trees, liquids) in plain colours.
     */
    public static boolean lowTraffic;

    @Override
    public void addSaveData(SaveData data) {
        data.addBoolean("lowTraffic", lowTraffic, "Upload discovered tiles without colours; the server colours them, without lighting");
    }

    @Override
    public void applyLoadData(LoadData data) {
        lowTraffic = data.getBoolean("lowTraffic", false);
    }
}

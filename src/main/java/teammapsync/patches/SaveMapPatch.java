package teammapsync.patches;

import necesse.engine.modLoader.annotations.ModMethodPatch;
import necesse.engine.network.client.ClientLevelManager;
import net.bytebuddy.asm.Advice;
import teammapsync.client.TeamMapClient;

/** Keeps team markers out of the personal {@code icons.mapdata}; the server owns them. */
@ModMethodPatch(target = ClientLevelManager.class, name = "saveMap", arguments = {})
public class SaveMapPatch {

    @Advice.OnMethodEnter
    static void onEnter(@Advice.This ClientLevelManager levelManager) {
        TeamMapClient.beforeMapSave(levelManager);
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class)
    static void onExit(@Advice.This ClientLevelManager levelManager) {
        TeamMapClient.afterMapSave(levelManager);
    }
}

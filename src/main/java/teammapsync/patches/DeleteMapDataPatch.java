package teammapsync.patches;

import necesse.engine.modLoader.annotations.ModMethodPatch;
import necesse.engine.network.client.ClientLevelManager;
import net.bytebuddy.asm.Advice;
import teammapsync.client.TeamMapClient;

/** The {@code deletemapdata} command clears the marker list; that must not delete the team's markers. */
@ModMethodPatch(target = ClientLevelManager.class, name = "deleteMapData", arguments = {})
public class DeleteMapDataPatch {

    @Advice.OnMethodEnter
    static void onEnter(@Advice.This ClientLevelManager levelManager) {
        TeamMapClient.onMapDataDeleted(levelManager);
    }
}

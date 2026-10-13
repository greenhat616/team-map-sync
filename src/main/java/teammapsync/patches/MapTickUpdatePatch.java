package teammapsync.patches;

import necesse.engine.modLoader.annotations.ModMethodPatch;
import necesse.level.maps.mapData.ClientDiscoveredMap;
import net.bytebuddy.asm.Advice;
import teammapsync.client.MapRecorder;

/** Marks the refresh pass for tiles that changed near the player (mined walls, placed objects). */
@ModMethodPatch(target = ClientDiscoveredMap.class, name = "tickUpdate", arguments = {int.class, int.class})
public class MapTickUpdatePatch {

    @Advice.OnMethodEnter
    static void onEnter(@Advice.This ClientDiscoveredMap map) {
        MapRecorder.begin(map);
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class)
    static void onExit() {
        MapRecorder.end();
    }
}

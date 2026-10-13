package teammapsync.patches;

import necesse.engine.modLoader.annotations.ModMethodPatch;
import necesse.level.maps.Level;
import necesse.level.maps.mapData.ClientDiscoveredMap;
import net.bytebuddy.asm.Advice;
import teammapsync.client.MapRecorder;

/** Marks the player's own discovery pass, so only those tile writes are recorded. */
@ModMethodPatch(target = ClientDiscoveredMap.class, name = "tickDiscovery", arguments = {Level.class, int.class, int.class})
public class MapTickDiscoveryPatch {

    @Advice.OnMethodEnter
    static void onEnter(@Advice.This ClientDiscoveredMap map) {
        MapRecorder.begin(map);
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class)
    static void onExit() {
        MapRecorder.end();
    }
}

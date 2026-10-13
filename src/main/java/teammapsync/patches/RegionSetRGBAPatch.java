package teammapsync.patches;

import necesse.engine.modLoader.annotations.ModMethodPatch;
import necesse.level.maps.mapData.ClientDiscoveredMapRegion;
import net.bytebuddy.asm.Advice;
import teammapsync.client.MapRecorder;

/**
 * Every discovered or refreshed map tile goes through this method. Outside a recorded discovery pass the advice
 * costs one static field read.
 */
@ModMethodPatch(target = ClientDiscoveredMapRegion.class, name = "setRGBA", arguments = {int.class, int.class, int.class})
public class RegionSetRGBAPatch {

    @Advice.OnMethodExit
    static void onExit(@Advice.This ClientDiscoveredMapRegion region, @Advice.Argument(0) int tileX, @Advice.Argument(1) int tileY,
                       @Advice.Argument(2) int argb, @Advice.Return boolean changed) {
        if (changed && MapRecorder.recording) {
            MapRecorder.record(region, tileX, tileY, argb);
        }
    }
}

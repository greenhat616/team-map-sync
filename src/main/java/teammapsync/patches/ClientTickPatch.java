package teammapsync.patches;

import necesse.engine.modLoader.annotations.ModMethodPatch;
import necesse.engine.network.client.Client;
import net.bytebuddy.asm.Advice;
import teammapsync.client.TeamMapClient;

/** Periodic hook on the game thread, once per game tick. */
@ModMethodPatch(target = Client.class, name = "tick", arguments = {})
public class ClientTickPatch {

    @Advice.OnMethodExit
    static void onExit(@Advice.This Client client) {
        TeamMapClient.tick(client);
    }
}

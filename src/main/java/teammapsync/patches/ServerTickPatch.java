package teammapsync.patches;

import necesse.engine.modLoader.annotations.ModMethodPatch;
import necesse.engine.network.server.Server;
import net.bytebuddy.asm.Advice;
import teammapsync.server.TeamMapServer;

/** Periodic hook on the server thread, once per server tick. */
@ModMethodPatch(target = Server.class, name = "tick", arguments = {})
public class ServerTickPatch {

    @Advice.OnMethodExit
    static void onExit(@Advice.This Server server) {
        TeamMapServer.tick(server);
    }
}

package teammapsync.patches;

import necesse.engine.modLoader.annotations.ModMethodPatch;
import necesse.engine.network.client.Client;
import net.bytebuddy.asm.Advice;
import teammapsync.client.TeamMapClient;

/**
 * Every vanilla disconnect path, closing the game included ({@code saveAndClose} → {@code instantDisconnect}), ends
 * here; received map tiles not yet written into the map are saved first.
 */
@ModMethodPatch(target = Client.class, name = "privateDisconnect", arguments = {boolean.class})
public class ClientDisconnectPatch {

    @Advice.OnMethodEnter
    static void onEnter(@Advice.This Client client) {
        TeamMapClient.onDisconnect(client);
    }
}

package teammapsync.patches;

import necesse.engine.modLoader.annotations.ModMethodPatch;
import necesse.engine.save.SaveData;
import necesse.engine.team.PlayerTeam;
import net.bytebuddy.asm.Advice;
import teammapsync.server.TeamKeys;

/** Writes the team's persistent sync id into its vanilla save record. */
@ModMethodPatch(target = PlayerTeam.class, name = "addSaveData", arguments = {SaveData.class})
public class PlayerTeamSavePatch {

    @Advice.OnMethodExit
    static void onExit(@Advice.This PlayerTeam team, @Advice.Argument(0) SaveData save) {
        TeamKeys.onSave(team, save);
    }
}

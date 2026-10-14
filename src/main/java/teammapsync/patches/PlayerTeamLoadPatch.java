package teammapsync.patches;

import necesse.engine.modLoader.annotations.ModConstructorPatch;
import necesse.engine.save.LoadData;
import necesse.engine.team.PlayerTeam;
import necesse.engine.team.TeamManager;
import net.bytebuddy.asm.Advice;
import teammapsync.server.TeamKeys;

/** Reads the team's persistent sync id back when vanilla loads the team. */
@ModConstructorPatch(target = PlayerTeam.class, arguments = {TeamManager.class, int.class, LoadData.class})
public class PlayerTeamLoadPatch {

    @Advice.OnMethodExit
    static void onExit(@Advice.This PlayerTeam team, @Advice.Argument(2) LoadData load) {
        TeamKeys.onLoad(team, load);
    }
}

package teammapsync.patches;

import necesse.engine.modLoader.annotations.ModConstructorPatch;
import necesse.engine.network.client.Client;
import necesse.engine.network.client.ClientLevelManager;
import necesse.gfx.forms.Form;
import necesse.gfx.forms.presets.EditMapMarkerForm;
import net.bytebuddy.asm.Advice;
import teammapsync.client.TeamMarkerCheckbox;

/** Adds the "share with team" checkbox below the marker form's buttons. */
@ModConstructorPatch(target = EditMapMarkerForm.class, arguments = {Client.class, int.class, ClientLevelManager.MapMarker.class, boolean.class})
public class EditMapMarkerFormPatch {

    @Advice.OnMethodExit
    static void onExit(@Advice.This EditMapMarkerForm form, @Advice.FieldValue("inputForm") Form inputForm, @Advice.Argument(0) Client client,
                       @Advice.Argument(2) ClientLevelManager.MapMarker marker, @Advice.Argument(3) boolean isNewMarker) {
        TeamMarkerCheckbox.add(form, inputForm, client, marker, isNewMarker);
    }
}

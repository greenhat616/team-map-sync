package teammapsync.client;

import necesse.engine.localization.Localization;
import necesse.engine.network.client.Client;
import necesse.engine.network.client.ClientLevelManager;
import necesse.engine.window.WindowManager;
import necesse.gfx.forms.Form;
import necesse.gfx.forms.components.FormCheckBox;
import necesse.gfx.forms.presets.EditMapMarkerForm;

/** Adds the "share with team" checkbox to the vanilla marker edit form. Its choice applies when the form closes. */
public final class TeamMarkerCheckbox {

    private TeamMarkerCheckbox() {
    }

    public static void add(EditMapMarkerForm form, Form inputForm, Client client, ClientLevelManager.MapMarker marker, boolean isNewMarker) {
        TeamMapClient session = TeamMapClient.active(client);
        if (session == null || inputForm == null || marker == null) {
            return;
        }
        session.editing(marker, form);
        boolean checked = session.isShared(marker);
        if (isNewMarker && TeamMarkers.shareNewMarkers && !checked) {
            session.setShared(marker, true, form);
            checked = true;
        }
        int y = inputForm.getHeight();
        FormCheckBox box = inputForm.addComponent(new FormCheckBox(Localization.translate("teammapsync", "sharewithteam"),
                4, y, inputForm.getWidth() - 8, checked));
        box.onClicked(e -> {
            TeamMapClient current = TeamMapClient.active(client);
            if (current != null) {
                current.setShared(marker, e.from.checked, form);
                TeamMarkers.shareNewMarkers = e.from.checked;
            }
        });
        inputForm.setHeight(y + 24);
        // The form centred itself before the checkbox made it taller.
        inputForm.setPosMiddle(WindowManager.getWindow().getHudWidth() / 2, WindowManager.getWindow().getHudHeight() / 2);
    }
}

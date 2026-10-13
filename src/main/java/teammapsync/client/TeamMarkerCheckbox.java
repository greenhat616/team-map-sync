package teammapsync.client;

import necesse.engine.localization.Localization;
import necesse.engine.network.client.Client;
import necesse.engine.network.client.ClientLevelManager;
import necesse.engine.window.WindowManager;
import necesse.gfx.forms.Form;
import necesse.gfx.forms.components.FormCheckBox;

/** Adds the "share with team" checkbox to the vanilla marker edit form. */
public final class TeamMarkerCheckbox {

    private TeamMarkerCheckbox() {
    }

    public static void add(Form inputForm, Client client, ClientLevelManager.MapMarker marker, boolean isNewMarker) {
        TeamMapClient session = TeamMapClient.active(client);
        if (session == null || inputForm == null || marker == null) {
            return;
        }
        boolean checked = session.isShared(marker);
        if (isNewMarker && TeamMarkers.shareNewMarkers && !checked) {
            session.setShared(marker, true);
            checked = true;
        }
        int y = inputForm.getHeight();
        FormCheckBox box = inputForm.addComponent(new FormCheckBox(Localization.translate("teammapsync", "sharewithteam"),
                4, y, inputForm.getWidth() - 8, checked));
        box.onClicked(e -> {
            TeamMapClient current = TeamMapClient.active(client);
            if (current != null) {
                current.setShared(marker, e.from.checked);
                TeamMarkers.shareNewMarkers = e.from.checked;
            }
        });
        inputForm.setHeight(y + 24);
        // The form centred itself before the checkbox made it taller.
        inputForm.setPosMiddle(WindowManager.getWindow().getHudWidth() / 2, WindowManager.getWindow().getHudHeight() / 2);
    }
}

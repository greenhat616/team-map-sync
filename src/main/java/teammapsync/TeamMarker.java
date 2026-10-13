package teammapsync;

import java.util.UUID;
import java.util.regex.Pattern;

/** Immutable description of a shared map marker, as it travels over the network and sits in the team store. */
public final class TeamMarker {

    public static final int MAX_NAME_LENGTH = 50;
    private static final Pattern LEVEL_PATTERN = Pattern.compile("[a-z0-9-+]{1,50}");
    private static final Pattern ICON_PATTERN = Pattern.compile("[A-Za-z0-9_.-]{1,64}");

    public final UUID id;
    public final String icon;
    public final String name;
    public final String level;
    public final int tileX;
    public final int tileY;

    public TeamMarker(UUID id, String icon, String name, String level, int tileX, int tileY) {
        this.id = id;
        this.icon = icon;
        this.name = name;
        this.level = level;
        this.tileX = tileX;
        this.tileY = tileY;
    }

    /** Same marker with a new icon and name; position and level never change after creation. */
    public TeamMarker withLook(String icon, String name) {
        return new TeamMarker(this.id, icon, name, this.level, this.tileX, this.tileY);
    }

    public boolean isValid() {
        return id != null && icon != null && ICON_PATTERN.matcher(icon).matches()
                && name != null && name.length() <= MAX_NAME_LENGTH
                && isValidLevel(level);
    }

    public static boolean isValidLevel(String level) {
        return level != null && LEVEL_PATTERN.matcher(level).matches();
    }
}

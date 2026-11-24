package gg.modl.backend.player.data.punishment;

import gg.modl.backend.settings.data.PunishmentCategory;
import gg.modl.backend.settings.data.PunishmentType;
import org.jspecify.annotations.Nullable;
import java.util.Optional;

public final class IdentityRestriction {
    private static final String UNKNOWN = "Unknown";

    private IdentityRestriction() {
    }

    public static boolean canLift(PunishmentType type, PunishmentDataView data) {
        return (type.isPermanentUntilUsernameChange() && isKnown(data.blockedName()))
            || (type.isPermanentUntilSkinChange() && isKnown(data.blockedSkin()));
    }

    public static boolean isHeldBy(Punishment punishment) {
        return !PunishmentCategory.isCoreOrdinal(punishment.getTypeOrdinal())
            && (isKnown(punishment.data().blockedName()) || isKnown(punishment.data().blockedSkin()));
    }

    public static Optional<String> liftReason(Punishment punishment, @Nullable String username, @Nullable String skinHash) {
        if (!isHeldBy(punishment)) {
            return Optional.empty();
        }
        String blockedName = knownOrNull(punishment.data().blockedName());
        String currentName = knownOrNull(username);
        if (blockedName != null && currentName != null && !blockedName.equalsIgnoreCase(currentName)) {
            return Optional.of("Auto-pardoned: username changed from '" + blockedName + "' to '" + currentName + "'");
        }
        String blockedSkin = knownOrNull(punishment.data().blockedSkin());
        String currentSkin = knownOrNull(skinHash);
        if (blockedSkin != null && currentSkin != null && !blockedSkin.equals(currentSkin)) {
            return Optional.of("Auto-pardoned: skin changed");
        }
        return Optional.empty();
    }

    public static boolean isKnown(@Nullable String identity) {
        return knownOrNull(identity) != null;
    }

    public static @Nullable String knownOrNull(@Nullable String identity) {
        return identity != null && !identity.isBlank() && !UNKNOWN.equals(identity) ? identity : null;
    }
}

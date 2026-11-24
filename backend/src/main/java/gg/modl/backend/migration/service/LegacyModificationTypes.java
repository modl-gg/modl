package gg.modl.backend.migration.service;

import gg.modl.backend.player.data.punishment.PunishmentModificationType;
import org.jspecify.annotations.Nullable;
import java.util.Set;

public final class LegacyModificationTypes {
    private static final Set<String> PARDONS = Set.of("REMOVE", "REVOKE");

    private LegacyModificationTypes() {
    }

    @Nullable
    public static String current(@Nullable String sourceType) {
        return sourceType != null && PARDONS.contains(sourceType) ? PunishmentModificationType.MANUAL_PARDON.name() : sourceType;
    }
}

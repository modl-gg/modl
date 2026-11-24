package gg.modl.backend.player.service;

import gg.modl.backend.infrastructure.exception.ValidationException;
import gg.modl.backend.player.data.punishment.Punishment;
import gg.modl.backend.player.data.punishment.ResolvedCategory;
import org.jspecify.annotations.Nullable;
import java.util.Locale;

final class PardonTargets {
    static final String TYPE_MISMATCH = "PUNISHMENT_TYPE_MISMATCH";

    private PardonTargets() {
    }

    @Nullable
    static ResolvedCategory categoryOf(@Nullable String type) {
        if (type == null || type.isBlank()) {
            return null;
        }
        return switch (type.trim().toLowerCase(Locale.ROOT)) {
            case "ban" -> ResolvedCategory.BAN;
            case "mute" -> ResolvedCategory.MUTE;
            default -> throw new ValidationException("The punishment type to pardon must be ban or mute", "INVALID_ARGUMENT");
        };
    }

    static boolean matches(Punishment punishment, @Nullable ResolvedCategory expected) {
        return expected == null || punishment.enforcementCategory() == expected;
    }

    static void requireCategory(Punishment punishment, @Nullable ResolvedCategory expected) {
        if (expected != null && punishment.enforcementCategory() != expected) {
            throw new ValidationException("The punishment type does not match: " + punishment.getId() + " is not a "
                + expected.name().toLowerCase(Locale.ROOT), TYPE_MISMATCH);
        }
    }
}

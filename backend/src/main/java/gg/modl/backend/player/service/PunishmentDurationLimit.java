package gg.modl.backend.player.service;

import gg.modl.backend.infrastructure.exception.ValidationException;
import gg.modl.backend.infrastructure.validation.RequestValidationLimits;
import org.jspecify.annotations.Nullable;

final class PunishmentDurationLimit {
    private PunishmentDurationLimit() {
    }

    static void require(@Nullable Long durationMillis) {
        if (durationMillis != null && durationMillis > RequestValidationLimits.PUNISHMENT_DURATION_MAX_MS) {
            throw new ValidationException("Punishment duration is too long", "INVALID_ARGUMENT");
        }
    }
}

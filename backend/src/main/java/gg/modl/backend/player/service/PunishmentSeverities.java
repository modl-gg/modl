package gg.modl.backend.player.service;

import gg.modl.backend.infrastructure.exception.ValidationException;
import org.jspecify.annotations.Nullable;
import java.util.Locale;
import java.util.Set;

final class PunishmentSeverities {
    static final String SINGLE = "single";

    private static final Set<String> ACCEPTED = Set.of(
        "low", "lenient",
        "regular", "normal",
        "severe", "aggravated",
        SINGLE);

    private PunishmentSeverities() {
    }

    static void requireAccepted(@Nullable Object severity) {
        if (severity == null) {
            return;
        }
        if (!(severity instanceof String text) || !ACCEPTED.contains(text.toLowerCase(Locale.ROOT))) {
            throw new ValidationException("severity must be lenient, regular, severe, or single", "INVALID_ARGUMENT");
        }
    }
}

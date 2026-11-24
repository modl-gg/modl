package gg.modl.backend.settings.service;

import gg.modl.backend.infrastructure.exception.ConflictException;
import gg.modl.backend.settings.data.PunishmentType;
import org.jspecify.annotations.Nullable;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

final class PunishmentTypeNames {
    private static final Pattern SEPARATORS = Pattern.compile("[\\s_-]+");

    private PunishmentTypeNames() {
    }

    static void requireUnused(List<PunishmentType> current, String name, @Nullable Integer ownOrdinal) {
        String requested = comparable(name);
        boolean taken = current.stream()
            .filter(type -> ownOrdinal == null || !Objects.equals(type.getOrdinal(), ownOrdinal))
            .anyMatch(type -> hasComparableName(type, requested));
        if (taken) {
            throw new ConflictException("A punishment type with this name already exists");
        }
    }

    private static boolean hasComparableName(PunishmentType type, String requested) {
        String existing = type.getName();
        return existing != null && comparable(existing).equals(requested);
    }

    private static String comparable(String name) {
        return SEPARATORS.matcher(name.strip().toLowerCase(Locale.ROOT)).replaceAll("-");
    }
}

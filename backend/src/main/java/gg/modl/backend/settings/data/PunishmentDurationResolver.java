package gg.modl.backend.settings.data;

import org.jspecify.annotations.Nullable;
import java.util.HashMap;
import java.util.Map;

public final class PunishmentDurationResolver {
    private static final Map<Integer, PunishmentType> DEFAULT_TYPES_BY_ORDINAL = indexDefaults();

    private PunishmentDurationResolver() {
    }

    public static @Nullable DurationDetail resolveDetail(PunishmentType type, String severity, String offenseLevel) {
        DurationDetail detail = type.getDurationDetail(severity, offenseLevel);
        if (detail != null) {
            return detail;
        }
        Integer ordinal = type.getOrdinal();
        if (ordinal == null) {
            return null;
        }
        PunishmentType defaultType = DEFAULT_TYPES_BY_ORDINAL.get(ordinal);
        return defaultType != null ? defaultType.getDurationDetail(severity, offenseLevel) : null;
    }

    private static Map<Integer, PunishmentType> indexDefaults() {
        Map<Integer, PunishmentType> byOrdinal = new HashMap<>();
        for (PunishmentType type : DefaultPunishmentTypes.getAll()) {
            Integer ordinal = type.getOrdinal();
            if (ordinal != null) {
                byOrdinal.put(ordinal, type);
            }
        }
        return byOrdinal;
    }
}

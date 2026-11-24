package gg.modl.backend.infrastructure.util;

import lombok.experimental.UtilityClass;

@UtilityClass
public class DateRangeUtil {

    public int resolveRangeDays(String range) {
        String normalized = (range == null || range.isBlank()) ? "30d" : range;
        return switch (normalized) {
            case "7d" -> 7;
            case "90d" -> 90;
            case "365d", "1y" -> 365;
            default -> 30;
        };
    }
}

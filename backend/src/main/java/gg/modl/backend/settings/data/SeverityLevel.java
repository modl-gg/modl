package gg.modl.backend.settings.data;

import org.jspecify.annotations.Nullable;
import java.util.List;
import java.util.Locale;

public final class SeverityLevel {
    public static final List<String> LEVELS = List.of("low", "regular", "severe");

    private SeverityLevel() {
    }

    public static String normalize(@Nullable String severity) {
        if (severity == null) {
            return "regular";
        }
        return switch (severity.toLowerCase(Locale.ROOT)) {
            case "low", "lenient" -> "low";
            case "regular", "normal" -> "regular";
            case "severe", "aggravated" -> "severe";
            default -> "regular";
        };
    }

    public static <T extends @Nullable Object> T select(@Nullable String severity, T low, T regular, T severe) {
        return switch (normalize(severity)) {
            case "low" -> low;
            case "severe" -> severe;
            default -> regular;
        };
    }
}

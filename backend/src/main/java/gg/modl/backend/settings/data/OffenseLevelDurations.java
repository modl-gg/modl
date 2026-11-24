package gg.modl.backend.settings.data;

import jakarta.validation.Valid;
import org.jspecify.annotations.Nullable;
import java.util.List;
import java.util.Locale;

public record OffenseLevelDurations(
    @Valid @Nullable DurationDetail first,
    @Valid @Nullable DurationDetail medium,
    @Valid @Nullable DurationDetail habitual
) {
    public static final List<String> OFFENSE_LEVELS = List.of("first", "medium", "habitual");

    public @Nullable DurationDetail getForOffenseLevel(String offenseLevel) {
        return switch (offenseLevel.toLowerCase(Locale.ROOT)) {
            case "first" -> first;
            case "medium" -> medium;
            case "habitual" -> habitual;
            default -> first;
        };
    }
}

package gg.modl.backend.settings.data;

import jakarta.validation.Valid;
import org.jspecify.annotations.Nullable;

public record PunishmentDurations(
    @Valid @Nullable OffenseLevelDurations low,
    @Valid @Nullable OffenseLevelDurations regular,
    @Valid @Nullable OffenseLevelDurations severe
) {
    public @Nullable DurationDetail getDuration(String severity, String offenseLevel) {
        OffenseLevelDurations severityDurations = getForSeverity(severity);
        return severityDurations != null ? severityDurations.getForOffenseLevel(offenseLevel) : null;
    }

    public @Nullable OffenseLevelDurations getForSeverity(String severity) {
        return SeverityLevel.select(severity, low, regular, severe);
    }
}

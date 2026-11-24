package gg.modl.backend.settings.data;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import gg.modl.backend.infrastructure.validation.RequestValidationLimits;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.Locale;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class PunishmentType {
    private @Nullable Integer id;
    private @Nullable String name;
    private @Nullable String category;
    private @Nullable Boolean customizable;
    private @Nullable Integer ordinal;

    @Valid
    private @Nullable PunishmentDurations durations;

    private @Nullable Boolean singleSeverityPunishment;
    @Valid
    private @Nullable OffenseLevelDurations singleSeverityDurations;
    @Min(RequestValidationLimits.PUNISHMENT_POINTS_MIN)
    @Max(RequestValidationLimits.PUNISHMENT_POINTS_MAX)
    private @Nullable Integer singleSeverityPoints;

    @Valid
    private @Nullable PunishmentPoints points;
    @Min(RequestValidationLimits.PUNISHMENT_POINTS_MIN)
    @Max(RequestValidationLimits.PUNISHMENT_POINTS_MAX)
    private @Nullable Integer customPoints;

    private @Nullable String staffDescription;
    private @Nullable String playerDescription;

    private @Nullable Boolean canBeAltBlocking;
    private @Nullable Boolean canBeStatWiping;
    private @Nullable Boolean appealable;

    private @Nullable AppealForm appealForm;

    private @Nullable Boolean permanentUntilSkinChange;
    private @Nullable Boolean permanentUntilUsernameChange;

    public boolean isSocial() {
        return "Social".equalsIgnoreCase(category);
    }

    public boolean isGameplay() {
        return "Gameplay".equalsIgnoreCase(category);
    }

    public boolean isAdministrative() {
        return "Administrative".equalsIgnoreCase(category);
    }

    public boolean isBan() {
        PunishmentCategory coreCategory = PunishmentCategory.fromCoreOrdinal(ordinal);
        if (coreCategory != null) {
            return coreCategory == PunishmentCategory.BAN;
        }
        return category != null && category.toLowerCase(Locale.ROOT).contains("ban");
    }

    public boolean isMute() {
        PunishmentCategory coreCategory = PunishmentCategory.fromCoreOrdinal(ordinal);
        if (coreCategory != null) {
            return coreCategory == PunishmentCategory.MUTE;
        }
        return category != null && category.toLowerCase(Locale.ROOT).contains("mute");
    }

    public boolean isKick() {
        PunishmentCategory coreCategory = PunishmentCategory.fromCoreOrdinal(ordinal);
        if (coreCategory != null) {
            return coreCategory == PunishmentCategory.KICK;
        }
        return !restrictsIdentity() && category != null && category.toLowerCase(Locale.ROOT).contains("kick");
    }

    public int getPointsForSeverity(@Nullable String severity) {
        if (customPoints != null) {
            return customPoints;
        }
        if (singleSeverityPoints != null) {
            return singleSeverityPoints;
        }
        if (points != null) {
            return points.getForSeverity(severity);
        }
        return 0;
    }

    public long getDurationMillis(String severity, String offenseLevel) {
        if (isSingleSeverityPunishment() && singleSeverityDurations != null) {
            DurationDetail detail = singleSeverityDurations.getForOffenseLevel(offenseLevel);
            return detail != null ? detail.toMilliseconds() : 0L;
        }
        if (durations != null) {
            DurationDetail detail = durations.getDuration(severity, offenseLevel);
            return detail != null ? detail.toMilliseconds() : 0L;
        }
        return 0L;
    }

    public boolean isSingleSeverityPunishment() {
        return singleSeverityPunishment != null && singleSeverityPunishment;
    }

    public @Nullable DurationDetail getDurationDetail(String severity, String offenseLevel) {
        if (isSingleSeverityPunishment() && singleSeverityDurations != null) {
            return singleSeverityDurations.getForOffenseLevel(offenseLevel);
        }
        if (durations != null) {
            return durations.getDuration(severity, offenseLevel);
        }
        return null;
    }

    public boolean isCustomizable() {
        return customizable != null && customizable;
    }

    public boolean isCanBeAltBlocking() {
        return canBeAltBlocking != null && canBeAltBlocking;
    }

    public boolean isCanBeStatWiping() {
        return canBeStatWiping != null && canBeStatWiping;
    }

    public boolean isAppealable() {
        return appealable != null && appealable;
    }

    public boolean isPermanentUntilSkinChange() {
        return permanentUntilSkinChange != null && permanentUntilSkinChange;
    }

    public boolean isPermanentUntilUsernameChange() {
        return permanentUntilUsernameChange != null && permanentUntilUsernameChange;
    }

    public boolean acceptsAiSuggestions() {
        return SeverityLevel.LEVELS.stream().allMatch(this::acceptsAiSuggestion);
    }

    public boolean acceptsAiSuggestion(String severity) {
        if (PunishmentCategory.isCoreOrdinal(ordinal) || restrictsIdentity()) {
            return false;
        }
        return isKick() || OffenseLevelDurations.OFFENSE_LEVELS.stream()
            .allMatch(level -> PunishmentDurationResolver.resolveDetail(this, SeverityLevel.normalize(severity), level) != null);
    }

    public boolean restrictsIdentity() {
        return !PunishmentCategory.isCoreOrdinal(ordinal) && (isPermanentUntilUsernameChange() || isPermanentUntilSkinChange());
    }
}

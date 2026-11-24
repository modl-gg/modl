package gg.modl.backend.settings.data;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import org.jspecify.annotations.Nullable;
import java.util.Locale;

@JsonIgnoreProperties(ignoreUnknown = true)
public record DurationDetail(
    @Min(MIN_DURATION_VALUE)
    @Max(MAX_DURATION_VALUE)
    int value,
    @Pattern(regexp = "(?i)^(seconds?|minutes?|hours?|days?|weeks?|months?)?$")
    @Nullable String unit,
    @Pattern(regexp = "^(mute|ban|permanent mute|permanent ban)?$")
    @Nullable String type
) {
    private static final long MIN_DURATION_VALUE = 0L;
    private static final long MAX_DURATION_VALUE = 100_000_000L;

    public long toMilliseconds() {
        if (isPermanent()) {
            return -1L;
        }

        if (unit == null || unit.isEmpty()) {
            return -1L;
        }

        return switch (unit.toLowerCase(Locale.ROOT)) {
            case "seconds", "second" -> value * 1000L;
            case "minutes", "minute" -> value * 60L * 1000L;
            case "hours", "hour" -> value * 60L * 60L * 1000L;
            case "days", "day" -> value * 24L * 60L * 60L * 1000L;
            case "weeks", "week" -> value * 7L * 24L * 60L * 60L * 1000L;
            case "months", "month" -> value * 30L * 24L * 60L * 60L * 1000L;
            default -> -1L;
        };
    }

    public boolean isPermanent() {
        return "permanent ban".equals(type) || "permanent mute".equals(type);
    }

    public boolean isBan() {
        return "ban".equals(type) || "permanent ban".equals(type);
    }

    public boolean isMute() {
        return "mute".equals(type) || "permanent mute".equals(type);
    }
}

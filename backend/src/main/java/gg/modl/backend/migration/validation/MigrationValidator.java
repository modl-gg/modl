package gg.modl.backend.migration.validation;

import gg.modl.backend.database.StorableText;
import gg.modl.backend.infrastructure.validation.RegExpConstants;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

@Component
@Slf4j
public class MigrationValidator {
    public static final int MAX_PLAYER_RECORDS = 1_000_000;

    private static final Instant EARLIEST_DATE = Instant.parse("0001-01-01T00:00:00Z");
    private static final Instant LATEST_DATE = Instant.parse("9999-12-31T23:59:59.999Z");

    private static final Pattern UUID_PATTERN = Pattern.compile(RegExpConstants.UUID);
    private static final Pattern UUID_NO_DASHES_PATTERN = Pattern.compile("^[0-9a-fA-F]{32}$");

    public Optional<String> playersHeaderProblem(boolean playersIsArray, @Nullable Integer declaredPlayerCount) {
        if (!playersIsArray) {
            return Optional.of("'players' field must be an array");
        }
        if (declaredPlayerCount != null && declaredPlayerCount <= 0) {
            return Optional.of("Players array cannot be empty");
        }
        if (declaredPlayerCount != null && declaredPlayerCount > MAX_PLAYER_RECORDS) {
            return Optional.of("Players array exceeds maximum length of 1,000,000");
        }
        return Optional.empty();
    }

    public String missingPlayersProblem() {
        return "Missing 'players' field";
    }

    public boolean isValidUuid(String uuid) {
        if (uuid.isBlank()) {
            return false;
        }

        return UUID_PATTERN.matcher(uuid).matches() || UUID_NO_DASHES_PATTERN.matcher(uuid).matches();
    }

    public String normalizeUuid(String uuid) {
        String cleaned = uuid.replace("-", "").toLowerCase(Locale.ROOT);

        if (cleaned.length() != 32) {
            return uuid;
        }

        return cleaned.substring(0, 8) + "-" +
               cleaned.substring(8, 12) + "-" +
               cleaned.substring(12, 16) + "-" +
               cleaned.substring(16, 20) + "-" +
               cleaned.substring(20);
    }

    public String sanitizeString(String input, int maxCodePoints) {
        String cleaned = StorableText.strip(input).replace("\r", "").trim();
        if (cleaned.codePointCount(0, cleaned.length()) <= maxCodePoints) {
            return cleaned;
        }
        return cleaned.substring(0, cleaned.offsetByCodePoints(0, maxCodePoints)).trim();
    }

    public @Nullable Instant parseDate(@Nullable Object dateObj) {
        Instant parsed = readDate(dateObj);
        return parsed != null && !parsed.isBefore(EARLIEST_DATE) && !parsed.isAfter(LATEST_DATE) ? parsed : null;
    }

    private @Nullable Instant readDate(@Nullable Object dateObj) {
        if (dateObj == null) {
            return null;
        }

        if (dateObj instanceof Instant date) {
            return date;
        }

        if (dateObj instanceof Number number) {
            return epochToDate(number.longValue());
        }

        if (dateObj instanceof String dateStr) {
            try {
                return Instant.parse(dateStr);
            } catch (DateTimeParseException e) {
                try {
                    return epochToDate(Long.parseLong(dateStr));
                } catch (NumberFormatException ex) {
                    log.debug("Skipped an unparsable date in the import file");
                    return null;
                }
            }
        }

        return null;
    }

    private @Nullable Instant epochToDate(long timestamp) {
        if (timestamp > 100_000_000_000L) {
            return Instant.ofEpochMilli(timestamp);
        }
        if (timestamp < EARLIEST_DATE.getEpochSecond()) {
            return null;
        }
        return Instant.ofEpochSecond(timestamp);
    }
}

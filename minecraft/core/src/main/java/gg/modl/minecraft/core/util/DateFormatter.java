package gg.modl.minecraft.core.util;

import org.jspecify.annotations.Nullable;

import java.time.DateTimeException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

public final class DateFormatter {
    public static final String DEFAULT_PATTERN = "MM/dd/yyyy HH:mm";

    private final DateTimeFormatter formatter;

    private DateFormatter(DateTimeFormatter formatter) {
        this.formatter = formatter;
    }

    public static DateFormatter defaults() {
        return new DateFormatter(translate(DEFAULT_PATTERN).withZone(ZoneId.systemDefault()));
    }

    public static DateFormatter configured(@Nullable String pattern, @Nullable String timezoneId, PluginLogger logger) {
        return new DateFormatter(patternOrDefault(pattern, logger).withZone(zone(timezoneId, logger)));
    }

    public String format(Instant instant) {
        return formatter.format(instant);
    }

    private static DateTimeFormatter translate(String pattern) {
        return SimpleDatePattern.toFormatter(pattern, Locale.getDefault(Locale.Category.FORMAT));
    }

    private static DateTimeFormatter patternOrDefault(@Nullable String pattern, PluginLogger logger) {
        if (pattern == null || pattern.isEmpty()) return translate(DEFAULT_PATTERN);
        try {
            return translate(pattern);
        } catch (IllegalArgumentException invalid) {
            logger.warning("locale_config.date_format " + invalid.getMessage() + "; using " + DEFAULT_PATTERN + ".");
            return translate(DEFAULT_PATTERN);
        }
    }

    private static ZoneId zone(@Nullable String timezoneId, PluginLogger logger) {
        if (timezoneId == null || timezoneId.trim().isEmpty()) return ZoneId.systemDefault();
        try {
            return ZoneId.of(timezoneId.trim(), ZoneId.SHORT_IDS);
        } catch (DateTimeException unknown) {
            logger.warning("locale_config.timezone '" + timezoneId + "' is not a known time zone; using the server time zone.");
            return ZoneId.systemDefault();
        }
    }
}

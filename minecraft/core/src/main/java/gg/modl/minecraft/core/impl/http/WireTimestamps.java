package gg.modl.minecraft.core.impl.http;

import org.jspecify.annotations.Nullable;

import java.time.DateTimeException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAccessor;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;

public final class WireTimestamps {
    private static final List<DateTimeFormatter> ZONED_FALLBACKS = Arrays.asList(
            DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSSX", Locale.US),
            DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ssX", Locale.US));

    private WireTimestamps() {
    }

    public static boolean isAbsent(@Nullable String value) {
        if (value == null) return true;
        String trimmed = value.trim();
        return trimmed.isEmpty() || "null".equalsIgnoreCase(trimmed);
    }

    public static @Nullable Instant parse(@Nullable String value) {
        if (value == null || isAbsent(value)) return null;
        String trimmed = value.trim();
        Instant parsed = parseIso(trimmed);
        if (parsed != null) return parsed;
        try {
            return Instant.ofEpochMilli(Long.parseLong(trimmed));
        } catch (NumberFormatException notMillis) {
            return parseFallbacks(trimmed);
        }
    }

    private static @Nullable Instant parseIso(String text) {
        Instant instant = tryParse(text, DateTimeFormatter.ISO_INSTANT, Instant::from);
        return instant != null ? instant : tryParse(text, DateTimeFormatter.ISO_OFFSET_DATE_TIME,
                parsed -> OffsetDateTime.from(parsed).toInstant());
    }

    private static @Nullable Instant parseFallbacks(String text) {
        for (DateTimeFormatter format : ZONED_FALLBACKS) {
            Instant parsed = tryParse(text, format, accessor -> OffsetDateTime.from(accessor).toInstant());
            if (parsed != null) return parsed;
        }
        return null;
    }

    private static @Nullable Instant tryParse(String text, DateTimeFormatter format,
                                              Function<TemporalAccessor, Instant> toInstant) {
        try {
            return toInstant.apply(format.parse(text));
        } catch (DateTimeException | ArithmeticException unparsable) {
            return null;
        }
    }
}

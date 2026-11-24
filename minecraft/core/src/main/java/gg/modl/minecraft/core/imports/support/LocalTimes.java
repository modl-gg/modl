package gg.modl.minecraft.core.imports.support;

import org.jspecify.annotations.Nullable;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Locale;

public final class LocalTimes {
    private static final DateTimeFormatter LOCAL = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.ROOT);
    private static final DateTimeFormatter WITH_OFFSET = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss Z", Locale.ROOT);
    private static final int LOCAL_LENGTH = 19;

    private LocalTimes() {
    }

    public static @Nullable Long local(@Nullable String text, ZoneId zone) {
        if (text == null || text.trim().length() < LOCAL_LENGTH) return null;
        try {
            return LocalDateTime.parse(text.trim().substring(0, LOCAL_LENGTH), LOCAL).atZone(zone).toInstant().toEpochMilli();
        } catch (DateTimeParseException unreadable) {
            return null;
        }
    }

    public static @Nullable Long withOffset(@Nullable String text) {
        if (text == null) return null;
        try {
            return OffsetDateTime.parse(text.trim(), WITH_OFFSET).toInstant().toEpochMilli();
        } catch (DateTimeParseException unreadable) {
            return null;
        }
    }
}

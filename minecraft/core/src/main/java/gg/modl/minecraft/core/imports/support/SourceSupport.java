package gg.modl.minecraft.core.imports.support;

import gg.modl.minecraft.api.PlayerNames;
import gg.modl.minecraft.core.imports.source.SourceException;
import gg.modl.minecraft.core.util.Constants;
import org.jspecify.annotations.Nullable;

import java.util.Locale;
import java.util.regex.Pattern;

public final class SourceSupport {
    private static final Pattern COLOR_CODES = Pattern.compile("(?i)[§&][0-9A-FK-ORX]");
    private static final String CONSOLE_SENDER = "console";

    private SourceSupport() {
    }

    public static boolean hasText(@Nullable String value) {
        return value != null && !value.trim().isEmpty();
    }

    public static String textOr(@Nullable String value, String fallback) {
        String trimmed = trimmedOrNull(value);
        return trimmed != null ? trimmed : fallback;
    }

    public static @Nullable String trimmedOrNull(@Nullable String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    public static boolean isPlayerName(@Nullable String value) {
        return value != null && PlayerNames.isValid(value);
    }

    public static String issuer(@Nullable String sender) {
        if (!hasText(sender)) return Constants.DEFAULT_CONSOLE_NAME;
        String plain = COLOR_CODES.matcher(sender).replaceAll("").trim();
        if (plain.isEmpty() || CONSOLE_SENDER.equals(plain.toLowerCase(Locale.ROOT))) return Constants.DEFAULT_CONSOLE_NAME;
        return plain;
    }

    public static SourceException runningRefusal(String displayName) {
        return SourceException.locked(displayName + " keeps its database file open");
    }
}

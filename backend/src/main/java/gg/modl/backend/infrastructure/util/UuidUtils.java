package gg.modl.backend.infrastructure.util;

import gg.modl.backend.infrastructure.exception.ValidationException;
import gg.modl.backend.infrastructure.validation.RegExpConstants;
import org.jspecify.annotations.Nullable;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

public final class UuidUtils {

    private static final String UNDASHED_UUID_PATTERN = "^[0-9a-fA-F]{32}$";
    private static final Pattern CANONICAL_UUID = Pattern.compile(RegExpConstants.UUID);
    private static final String DASH_INSERT_PATTERN = "(.{8})(.{4})(.{4})(.{4})(.{12})";
    private static final String DASH_INSERT_REPLACEMENT = "$1-$2-$3-$4-$5";

    private UuidUtils() {
    }

    public static String normalize(String value) {
        return value.toLowerCase(Locale.ROOT);
    }

    public static @Nullable String normalizeIfPresent(@Nullable String value) {
        return value == null ? null : normalize(value);
    }

    public static UUID requireCanonical(String value) {
        if (!CANONICAL_UUID.matcher(value).matches()) {
            throw new ValidationException("A valid player UUID is required");
        }
        return UUID.fromString(value);
    }

    public static @Nullable String dashed(@Nullable String raw) {
        if (raw == null) {
            return null;
        }
        String candidate = raw.trim();
        if (candidate.matches(UNDASHED_UUID_PATTERN)) {
            candidate = candidate.replaceFirst(DASH_INSERT_PATTERN, DASH_INSERT_REPLACEMENT);
        }
        try {
            return UUID.fromString(candidate).toString();
        } catch (IllegalArgumentException invalid) {
            return null;
        }
    }
}

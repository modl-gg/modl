package gg.modl.backend.instance;

import org.jspecify.annotations.Nullable;
import java.util.Optional;

public final class ServerNames {
    private static final int MAX_LENGTH = 80;

    private ServerNames() {
    }

    public static Optional<String> normalize(@Nullable String name) {
        if (name == null || name.isBlank()) {
            return Optional.empty();
        }
        String trimmed = name.trim();
        if (trimmed.codePointCount(0, trimmed.length()) <= MAX_LENGTH) {
            return Optional.of(trimmed);
        }
        return Optional.of(trimmed.substring(0, trimmed.offsetByCodePoints(0, MAX_LENGTH)).trim());
    }
}

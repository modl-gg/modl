package gg.modl.backend.player.dto.request;

import gg.modl.backend.infrastructure.exception.ValidationException;
import org.jspecify.annotations.Nullable;

public record OnlinePlayerPage(@Nullable String afterPlayerId, int size) {
    public static final int MAX_SIZE = 500;
    private static final int MAX_CURSOR_LENGTH = 36;
    private static final String INVALID_CURSOR = "The online players cursor is not valid";

    public static OnlinePlayerPage of(@Nullable String cursor, @Nullable Integer limit) {
        int size = limit == null ? MAX_SIZE : Math.clamp(limit, 1, MAX_SIZE);
        if (cursor == null || cursor.isBlank()) {
            return new OnlinePlayerPage(null, size);
        }
        String trimmed = cursor.trim();
        if (trimmed.length() > MAX_CURSOR_LENGTH) {
            throw new ValidationException(INVALID_CURSOR, "INVALID_ARGUMENT");
        }
        return new OnlinePlayerPage(trimmed, size);
    }
}

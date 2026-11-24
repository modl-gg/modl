package gg.modl.backend.player.dto.response;

import org.jspecify.annotations.Nullable;
import java.time.Instant;

public record PlayerSearchResult(
    String uuid,
    String username,
    String status,
    @Nullable Instant lastOnline,
    boolean isOnline
) {
}

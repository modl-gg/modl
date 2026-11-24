package gg.modl.backend.log.repository;

import org.jspecify.annotations.Nullable;
import java.time.Instant;

public record ChatLogRow(
    long id,
    String playerId,
    @Nullable String username,
    String message,
    String serverName,
    Instant sentAt
) {
}

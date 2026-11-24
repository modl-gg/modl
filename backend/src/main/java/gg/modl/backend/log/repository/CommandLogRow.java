package gg.modl.backend.log.repository;

import org.jspecify.annotations.Nullable;
import java.time.Instant;

public record CommandLogRow(
    long id,
    String playerId,
    @Nullable String username,
    String command,
    String serverName,
    Instant sentAt
) {
}

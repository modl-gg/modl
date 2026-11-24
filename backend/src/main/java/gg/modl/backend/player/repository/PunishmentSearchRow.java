package gg.modl.backend.player.repository;

import org.jspecify.annotations.Nullable;
import java.time.Instant;

public record PunishmentSearchRow(
    String id,
    String playerUsername,
    int typeOrdinal,
    @Nullable String issuerName,
    @Nullable String issuerId,
    @Nullable String reason,
    Instant issued,
    Instant activeUntil
) {
    public boolean isActiveAt(Instant now) {
        return !activeUntil.isBefore(now);
    }
}

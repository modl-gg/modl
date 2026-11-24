package gg.modl.backend.player.data.punishment;

import org.jspecify.annotations.Nullable;
import java.time.Instant;
import java.util.Map;

public record PunishmentModification(
    String id,
    String type,
    Instant date,
    @Nullable String issuerName,
    @Nullable String issuerId,
    String reason,
    @Nullable Long effectiveDuration,
    @Nullable String appealTicketId,
    @Nullable Map<String, Object> data
) {
}

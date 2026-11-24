package gg.modl.backend.dashboard.dto.response;

import java.time.Instant;

public record RecentPunishmentResponse(
    String id,
    String playerName,
    String playerUuid,
    String type,
    String reason,
    String issuerName,
    Instant issued,
    boolean active
) {
}

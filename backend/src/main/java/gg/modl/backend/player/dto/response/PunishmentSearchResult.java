package gg.modl.backend.player.dto.response;

import java.time.Instant;

public record PunishmentSearchResult(
    String id,
    String playerName,
    int typeOrdinal,
    String status,
    Instant issued
) {
}

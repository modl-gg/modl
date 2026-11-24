package gg.modl.backend.player.dto.response;

import org.jspecify.annotations.Nullable;
import java.time.Instant;
import java.util.List;
import java.util.Map;

public record PunishmentView(
    String id,
    String issuerName,
    @Nullable Instant issued,
    @Nullable Instant started,
    int typeOrdinal,
    String type,
    List<Map<String, Object>> modifications,
    List<Map<String, Object>> notes,
    List<Map<String, Object>> evidence,
    List<String> attachedTicketIds,
    Map<String, Object> data,
    @Nullable String playerUuid,
    @Nullable String playerName
) {
    public PunishmentView withPlayer(@Nullable String playerUuid, @Nullable String playerName) {
        return new PunishmentView(id, issuerName, issued, started, typeOrdinal, type,
            modifications, notes, evidence, attachedTicketIds, data, playerUuid, playerName);
    }
}

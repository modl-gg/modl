package gg.modl.backend.audit.dto.response;

import gg.modl.backend.player.data.punishment.PunishmentPhase;
import org.jspecify.annotations.Nullable;
import java.time.Instant;
import java.util.List;

public record AuditPunishmentResponse(
    String id,
    String playerId,
    String playerName,
    String type,
    int typeOrdinal,
    String category,
    String staffName,
    @Nullable String reason,
    @Nullable Long duration,
    Instant issued,
    @Nullable Instant started,
    @Nullable Instant expires,
    PunishmentPhase phase,
    boolean hasEvidence,
    int evidenceCount,
    List<EvidenceItem> evidence,
    List<String> attachedTicketIds,
    @Nullable String effectiveCategory
) {
    public record EvidenceItem(
        @Nullable String text,
        @Nullable String url,
        String type,
        @Nullable String fileName
    ) {}
}

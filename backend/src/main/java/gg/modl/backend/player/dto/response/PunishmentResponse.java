package gg.modl.backend.player.dto.response;

import gg.modl.backend.player.data.punishment.PunishmentEvidence;
import gg.modl.backend.player.data.punishment.PunishmentModification;
import gg.modl.backend.player.data.punishment.PunishmentNote;
import org.jspecify.annotations.Nullable;
import java.time.Instant;
import java.util.List;

public record PunishmentResponse(
    String id,
    String type,
    int typeOrdinal,
    String issuerName,
    Instant issued,
    @Nullable Instant started,
    boolean isAppealable,
    @Nullable String reason,
    @Nullable String severity,
    @Nullable String status,
    boolean active,
    @Nullable Instant expires,
    @Nullable String playerUuid,
    @Nullable String playerUsername,
    @Nullable Boolean altBlocking,
    @Nullable Boolean statWiping,
    @Nullable String effectiveCategory,
    List<PunishmentModification> modifications,
    List<PunishmentNote> notes,
    List<PunishmentEvidence> evidence,
    List<String> attachedTicketIds,
    @Nullable Long duration
) {
}

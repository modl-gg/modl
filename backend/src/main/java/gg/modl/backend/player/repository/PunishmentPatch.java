package gg.modl.backend.player.repository;

import gg.modl.backend.ticket.data.AppealWorkflowStatus;
import java.time.Instant;
import lombok.Builder;
import org.jspecify.annotations.Nullable;

@Builder
public record PunishmentPatch(
    @Nullable String status,
    @Nullable Long duration,
    @Nullable Boolean altBlocking,
    @Nullable Boolean wipeAfterExpiry,
    @Nullable Instant statWipeCompletedAt,
    @Nullable String appealOutcome,
    @Nullable String appealTicketId
) {
    public static final PunishmentPatch NONE = PunishmentPatch.builder().build();

    public static PunishmentPatch appealDecision(AppealWorkflowStatus decision, String appealTicketId) {
        if (!decision.isDecision()) {
            throw new IllegalArgumentException(decision + " is not an appeal decision");
        }
        return PunishmentPatch.builder().appealOutcome(decision.getDisplayName()).appealTicketId(appealTicketId).build();
    }

    public PunishmentPatch withAppealDecision(AppealWorkflowStatus decision, String appealTicketId) {
        PunishmentPatch decided = appealDecision(decision, appealTicketId);
        return new PunishmentPatch(status, duration, altBlocking, wipeAfterExpiry, statWipeCompletedAt,
            decided.appealOutcome(), decided.appealTicketId());
    }
}

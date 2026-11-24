package gg.modl.backend.ticket.service;

import gg.modl.backend.ticket.data.AppealWorkflowStatus;
import gg.modl.backend.ticket.data.TicketCategory;
import gg.modl.backend.ticket.data.TicketStatus;
import gg.modl.backend.ticket.repository.TicketHeaderPatch;
import org.jspecify.annotations.Nullable;
import java.time.Instant;

public final class TicketLifecycle {
    private TicketLifecycle() {
    }

    public static TicketHeaderPatch.TicketHeaderPatchBuilder change(TicketCategory type, @Nullable AppealWorkflowStatus workflow,
                                                                    TicketStatus status, Instant now) {
        TicketHeaderPatch.TicketHeaderPatchBuilder patch = TicketHeaderPatch.builder()
            .updatedAt(now)
            .status(status)
            .locked(status.isTerminal());
        if (type == TicketCategory.APPEAL) {
            AppealWorkflowStatus next = AppealWorkflowStatus.afterLifecycleChange(workflow, status);
            if (next != null) {
                patch.appealWorkflowStatus(next);
            }
        }
        return patch;
    }
}

package gg.modl.backend.audit.repository;

import gg.modl.backend.ticket.data.TicketCategory;
import gg.modl.backend.ticket.data.TicketStatus;
import org.jspecify.annotations.Nullable;
import java.time.Instant;

public record StaffTicketDetail(
    String id,
    @Nullable String subject,
    TicketCategory category,
    TicketStatus status,
    Instant createdAt,
    Instant firstReplyAt,
    Instant lastActivityAt
) {
}

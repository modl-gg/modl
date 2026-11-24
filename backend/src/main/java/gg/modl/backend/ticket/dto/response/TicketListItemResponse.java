package gg.modl.backend.ticket.dto.response;

import gg.modl.backend.ticket.data.TicketReply;
import org.jspecify.annotations.Nullable;
import java.time.Instant;
import java.util.List;

public record TicketListItemResponse(
    String id,
    String subject,
    String status,
    String reportedBy,
    String reportedByName,
    Instant date,
    String category,
    boolean locked,
    String type,
    @Nullable TicketReply lastReply,
    int replyCount,
    List<String> tags,
    List<String> assignedTo,
    boolean hidden
) {
}

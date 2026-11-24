package gg.modl.backend.ticket.dto.response;

import org.jspecify.annotations.Nullable;
import java.time.Instant;

public record SubscriptionUpdateResponse(
    String id,
    String ticketId,
    String ticketTitle,
    @Nullable String replyContent,
    @Nullable String replyBy,
    Instant replyAt,
    boolean isStaffReply,
    boolean isRead,
    @Nullable Integer additionalCount
) {
}

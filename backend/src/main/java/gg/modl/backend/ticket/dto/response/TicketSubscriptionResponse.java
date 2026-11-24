package gg.modl.backend.ticket.dto.response;

import java.time.Instant;

public record TicketSubscriptionResponse(
    String ticketId,
    String ticketTitle,
    Instant subscribedAt
) {
}

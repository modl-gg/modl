package gg.modl.backend.ticket.dto.response;

import org.jspecify.annotations.Nullable;

public record QuickResponseResult(
    boolean success,
    String message,
    @Nullable String ticketId,
    @Nullable String actionName,
    boolean ticketClosed,
    boolean punishmentIssued,
    @Nullable String appealOutcome
) {}

package gg.modl.backend.ticket.repository;

import org.jspecify.annotations.Nullable;
import java.time.Instant;

public record TicketVerificationRecord(
    String id,
    String ticketId,
    @Nullable String tokenHash,
    @Nullable String codeHash,
    @Nullable String email,
    int failedAttempts,
    Instant expiresAt
) {
}

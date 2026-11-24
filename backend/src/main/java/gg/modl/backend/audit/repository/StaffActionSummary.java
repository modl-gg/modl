package gg.modl.backend.audit.repository;

import java.time.Instant;

public record StaffActionSummary(
    String source,
    long totalActions,
    long ticketActions,
    long moderationActions,
    Instant lastActiveAt
) {
}

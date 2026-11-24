package gg.modl.backend.audit.dto.response;

import java.time.Instant;

public record StaffPerformanceResponse(
    String id,
    String username,
    String role,
    int totalActions,
    int ticketResponses,
    int punishmentsIssued,
    int avgResponseTime,
    Instant lastActive
) {
}

package gg.modl.backend.ticket.dto.response;

import org.jspecify.annotations.Nullable;
import java.time.Instant;
import java.util.List;

public record PlayerTicketResponse(
    String id,
    @Nullable String subject,
    String status,
    String type,
    Instant created,
    @Nullable String creatorName,
    @Nullable String creatorUuid,
    @Nullable String reportedPlayer,
    @Nullable String reportedPlayerUuid,
    boolean locked,
    List<String> tags,
    List<String> assignedTo
) {
}

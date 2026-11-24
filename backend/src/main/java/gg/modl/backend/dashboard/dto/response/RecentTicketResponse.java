package gg.modl.backend.dashboard.dto.response;

import org.jspecify.annotations.Nullable;
import java.time.Instant;

public record RecentTicketResponse(
    String id,
    String title,
    @Nullable String initialMessage,
    String status,
    String priority,
    Instant createdAt,
    String playerName,
    String type
) {
}

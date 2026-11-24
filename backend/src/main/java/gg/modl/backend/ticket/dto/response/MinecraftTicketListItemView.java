package gg.modl.backend.ticket.dto.response;

import org.jspecify.annotations.Nullable;
import java.time.Instant;
import java.util.List;

public record MinecraftTicketListItemView(
    String id,
    @Nullable String type,
    @Nullable String category,
    @Nullable String subject,
    @Nullable String status,
    @Nullable String playerName,
    @Nullable String playerUuid,
    @Nullable String priority,
    List<String> assignedTo,
    Instant createdAt,
    Instant updatedAt,
    boolean hasStaffResponse,
    int replyCount,
    boolean locked
) {
}

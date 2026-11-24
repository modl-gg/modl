package gg.modl.backend.ticket.dto.response;

import gg.modl.backend.ticket.data.Ticket;
import org.jspecify.annotations.Nullable;
import java.time.Instant;
import java.util.List;

public record MinecraftTicketDetailView(
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
    boolean locked,
    List<MinecraftTicketDetailReplyView> replies,
    @Nullable List<Ticket.ChatMessage> chatMessages,
    @Nullable String replayUrl
) {
}

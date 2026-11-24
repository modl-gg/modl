package gg.modl.backend.ticket.dto.response;

import gg.modl.backend.ticket.data.Ticket;
import org.jspecify.annotations.Nullable;
import java.time.Instant;
import java.util.List;

public record MinecraftReportView(
    String id,
    @Nullable String type,
    String reporterName,
    @Nullable String reporterUuid,
    @Nullable String reportedPlayerUuid,
    @Nullable String reportedPlayerName,
    @Nullable String subject,
    @Nullable String content,
    String status,
    String priority,
    Instant createdAt,
    List<String> assignedTo,
    @Nullable List<Ticket.ChatMessage> chatMessages,
    @Nullable String replayUrl
) {
}

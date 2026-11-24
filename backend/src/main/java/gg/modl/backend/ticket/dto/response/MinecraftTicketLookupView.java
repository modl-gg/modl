package gg.modl.backend.ticket.dto.response;

import org.jspecify.annotations.Nullable;
import java.time.Instant;

public record MinecraftTicketLookupView(
    String id,
    @Nullable String type,
    @Nullable String category,
    @Nullable String subject,
    @Nullable String status,
    @Nullable String playerName,
    @Nullable String playerUuid,
    Instant createdAt,
    @Nullable String firstReplyContent
) {
}

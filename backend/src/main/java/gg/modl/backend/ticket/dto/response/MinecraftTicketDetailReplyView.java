package gg.modl.backend.ticket.dto.response;

import org.jspecify.annotations.Nullable;
import java.time.Instant;

public record MinecraftTicketDetailReplyView(
    String id,
    @Nullable String content,
    @Nullable String authorName,
    @Nullable String authorId,
    boolean isStaff,
    Instant createdAt
) {
}

package gg.modl.backend.ticket.dto.response;

import org.jspecify.annotations.Nullable;
import java.time.Instant;

public record MinecraftPlayerTicketView(
    String id,
    @Nullable String type,
    @Nullable String category,
    @Nullable String subject,
    @Nullable String status,
    Instant createdAt,
    @Nullable String publicUrl
) {
}

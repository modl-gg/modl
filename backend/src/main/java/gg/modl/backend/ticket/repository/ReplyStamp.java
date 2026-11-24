package gg.modl.backend.ticket.repository;

import org.jspecify.annotations.Nullable;
import java.time.Instant;

public record ReplyStamp(String ticketId, int seq, Instant created, boolean staff, @Nullable String authorName) {
}

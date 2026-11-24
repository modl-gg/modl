package gg.modl.backend.staff.repository;

import org.jspecify.annotations.Nullable;
import java.time.Instant;

public record TicketSubscription(String ticketId, Instant subscribedAt, @Nullable Instant lastReadAt) {
}

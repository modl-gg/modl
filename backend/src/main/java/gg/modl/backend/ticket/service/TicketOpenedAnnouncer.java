package gg.modl.backend.ticket.service;

import gg.modl.backend.database.UnitOfWork;
import gg.modl.backend.player.service.StaffNotices;
import gg.modl.backend.player.service.SyncProtoFactory;
import gg.modl.backend.realtime.publish.RealtimeEventPublisher;
import gg.modl.backend.server.data.Server;
import gg.modl.backend.ticket.data.Ticket;
import gg.modl.backend.ticket.data.TicketReply;
import gg.modl.backend.webhook.WebhookEvent;
import gg.modl.backend.webhook.WebhookPublisher;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class TicketOpenedAnnouncer {
    private static final String UNKNOWN_CREATOR = "Unknown";

    private final UnitOfWork unitOfWork;
    private final WebhookPublisher webhookPublisher;
    private final RealtimeEventPublisher realtimeEventPublisher;
    private final SyncProtoFactory syncProtoFactory;
    private final StaffNotices staffNotices;

    public void opened(Server server, Ticket ticket) {
        Map<String, String> webhookVariables = webhookVariables(ticket);
        TicketReply opening = ticket.getReplies().isEmpty() ? null : ticket.getReplies().getFirst();
        unitOfWork.afterCommit(() -> {
            webhookPublisher.publish(WebhookEvent.NEW_TICKETS, webhookVariables);
            staffNotices.ticketCreated(ticket, opening).ifPresent(notice -> realtimeEventPublisher.pushStaffNotifications(
                server, List.of(syncProtoFactory.toStaffNotification(notice))));
        });
    }

    private static Map<String, String> webhookVariables(Ticket ticket) {
        String category = ticket.getType().getDisplayName();
        return Map.of(
            "id", ticket.getId(),
            "type", category,
            "title", Objects.requireNonNullElse(ticket.getSubject(), category),
            "priority", ticket.getPriority().name(),
            "category", category,
            "submittedBy", Objects.requireNonNullElse(ticket.getCreatorName(), UNKNOWN_CREATOR)
        );
    }
}

package gg.modl.backend.ticket.service;

import gg.modl.backend.infrastructure.exception.ConflictException;
import gg.modl.backend.infrastructure.exception.ResourceNotFoundException;
import gg.modl.backend.ticket.repository.ThreadSize;
import gg.modl.backend.ticket.data.Ticket;
import gg.modl.backend.ticket.data.TicketReply;
import gg.modl.backend.ticket.repository.TicketRepository;
import gg.modl.backend.ticket.repository.TicketSummary;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class TicketReplyAppender {
    private final TicketRepository ticketRepository;
    private final TicketUploads ticketUploads;

    public TicketReply appendUnlessLocked(Ticket ticket, TicketReply reply, String lockedMessage, Instant now) {
        if (ticket.isLocked()) {
            throw new ConflictException(lockedMessage);
        }
        return append(ticket.getId(), reply, lockedMessage, now);
    }

    public TicketReply appendPlayerReply(String ticketId, TicketReply reply, String lockedMessage, Instant now) {
        ThreadSize thread = ticketRepository.findThreadSize(ticketId)
            .orElseThrow(() -> new ResourceNotFoundException("Not found"));
        if (thread.locked()) {
            throw new ConflictException(lockedMessage);
        }
        TicketReply stored = withAttachedUploads(reply);
        TicketInputLimits.requireRoomForPlayerReply(thread, ticketRepository.storedCharacters(stored));
        return appendStored(ticketId, stored, lockedMessage, now);
    }

    private TicketReply append(String ticketId, TicketReply reply, String lockedMessage, Instant now) {
        return appendStored(ticketId, withAttachedUploads(reply), lockedMessage, now);
    }

    private TicketReply withAttachedUploads(TicketReply reply) {
        return reply.toBuilder().attachments(ticketUploads.attach(reply.getAttachments())).build();
    }

    private TicketReply appendStored(String ticketId, TicketReply stored, String lockedMessage, Instant now) {
        ticketRepository.appendReply(ticketId, stored, now);
        if (ticketRepository.findSummaries(List.of(ticketId)).stream().anyMatch(TicketSummary::isLocked)) {
            throw new ConflictException(lockedMessage);
        }
        return stored;
    }
}

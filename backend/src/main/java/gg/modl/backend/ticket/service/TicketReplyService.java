package gg.modl.backend.ticket.service;

import gg.modl.backend.database.UnitOfWork;
import gg.modl.backend.infrastructure.exception.ResourceNotFoundException;
import gg.modl.backend.server.data.Server;
import gg.modl.backend.ticket.data.Ticket;
import gg.modl.backend.ticket.data.TicketNote;
import gg.modl.backend.ticket.data.TicketReply;
import gg.modl.backend.ticket.dto.request.AddNoteRequest;
import gg.modl.backend.ticket.dto.request.AddReplyRequest;
import gg.modl.backend.ticket.repository.TicketRepository;
import gg.modl.backend.ticket.repository.TicketSummary;
import java.time.Clock;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class TicketReplyService {
    private static final String NOT_FOUND_MESSAGE = "Ticket not found";
    private static final String LOCKED_MESSAGE = "Ticket is locked and cannot accept new replies";

    private final TicketRepository ticketRepository;
    private final TicketNotificationService notificationService;
    private final TicketMessages ticketMessages;
    private final TicketReplyAppender replyAppender;
    private final TicketUploads ticketUploads;
    private final TicketChangeAuthorizer changeAuthorizer;
    private final UnitOfWork unitOfWork;
    private final Clock clock;

    public TicketReply addReply(Server server, String ticketId, AddReplyRequest request) {
        return unitOfWork.write(() -> {
            Ticket ticket = loadTicket(ticketId);
            Instant now = clock.instant();
            TicketReply reply = replyAppender.appendUnlessLocked(
                ticket, ticketMessages.staffReply(request, now), LOCKED_MESSAGE, now);
            unitOfWork.afterCommit(() -> notificationService.notifyTicketReply(server, ticket, reply));
            return reply;
        });
    }

    public TicketReply addPublicReply(Server server, String ticketId, String content, @Nullable List<Object> attachments) {
        return unitOfWork.write(() -> {
            TicketSummary ticket = loadSummary(ticketId);
            Instant now = clock.instant();
            TicketReply reply = ticketMessages.playerReply(ticket.getCreatorName(), content, attachments, now);
            return replyAppender.appendPlayerReply(ticket.getId(), reply, LOCKED_MESSAGE, now);
        });
    }

    public TicketNote addNote(Server server, String ticketId, AddNoteRequest request, String issuerName, @Nullable String staffEmail) {
        changeAuthorizer.requirePermitted(server, staffEmail, EnumSet.of(TicketChange.NOTE));
        return unitOfWork.write(() -> {
            TicketSummary ticket = loadSummary(ticketId);
            Instant now = clock.instant();
            TicketNote note = ticketMessages.requestedNote(request, issuerName, now);
            note.setAttachments(ticketUploads.attach(note.getAttachments()));
            ticketRepository.appendNote(ticket.getId(), note, now);
            return note;
        });
    }

    public List<String> addTag(Server server, String ticketId, String tag) {
        return unitOfWork.write(() -> {
            TicketSummary ticket = loadSummary(ticketId);
            if (ticket.getTags().contains(tag)) {
                return ticket.getTags();
            }
            ticketRepository.addTags(ticket.getId(), List.of(tag), clock.instant());
            return loadSummary(ticketId).getTags();
        });
    }

    public List<String> removeTag(Server server, String ticketId, String tag) {
        return unitOfWork.write(() -> {
            TicketSummary ticket = loadSummary(ticketId);
            if (!ticket.getTags().contains(tag)) {
                return ticket.getTags();
            }
            ticketRepository.removeTags(ticket.getId(), List.of(tag), clock.instant());
            return loadSummary(ticketId).getTags();
        });
    }

    private Ticket loadTicket(String ticketId) {
        return ticketRepository.findById(ticketId)
            .orElseThrow(() -> new ResourceNotFoundException(NOT_FOUND_MESSAGE));
    }

    private TicketSummary loadSummary(String ticketId) {
        return ticketRepository.findSummaries(List.of(ticketId)).stream().findFirst()
            .orElseThrow(() -> new ResourceNotFoundException(NOT_FOUND_MESSAGE));
    }
}

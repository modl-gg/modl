package gg.modl.backend.ticket.service;

import gg.modl.backend.database.UnitOfWork;
import gg.modl.backend.infrastructure.exception.ConflictException;
import gg.modl.backend.server.data.Server;
import gg.modl.backend.ticket.data.AppealWorkflowStatus;
import gg.modl.backend.ticket.data.Ticket;
import gg.modl.backend.ticket.data.TicketCategory;
import gg.modl.backend.ticket.data.TicketReply;
import gg.modl.backend.ticket.data.TicketStatus;
import gg.modl.backend.ticket.repository.TicketHeaderPatch;
import gg.modl.backend.ticket.repository.TicketRepository;
import java.time.Clock;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AppealWorkflowTransitionService {
    public static final String APPEAL_IN_PROGRESS = "APPEAL_IN_PROGRESS";

    private final TicketRepository ticketRepository;
    private final TicketNotificationService notificationService;
    private final UnitOfWork unitOfWork;
    private final Clock clock;

    public void applyOutcomeForPunishment(Server server, String appealId, String punishmentId,
                                          AppealWorkflowStatus outcome, String issuerName) {
        unitOfWork.write(() -> {
            Ticket appeal = ticketRepository.findById(appealId)
                .filter(ticket -> ticket.getType() == TicketCategory.APPEAL)
                .filter(ticket -> isLinkedToPunishment(ticket, punishmentId))
                .orElse(null);
            if (appeal == null || appeal.getAppealWorkflowStatus() == outcome) {
                return;
            }
            requireReopenBeforeChangingTerminalStatus(appeal.getAppealWorkflowStatus(), outcome);

            Instant now = clock.instant();
            TicketStatus lifecycleStatus = outcome.toTicketStatus();
            ticketRepository.updateHeader(appeal.getId(), appeal.getVersion(), TicketHeaderPatch.builder()
                .updatedAt(now)
                .appealWorkflowStatus(outcome)
                .status(lifecycleStatus)
                .locked(lifecycleStatus.isTerminal())
                .build());
            ticketRepository.appendReply(appeal.getId(), statusChangeReply(issuerName, outcome, now), now);

            boolean wasClosed = appeal.getStatus().isTerminal();
            if (!wasClosed && lifecycleStatus.isTerminal()) {
                Ticket closed = ticketRepository.findById(appeal.getId()).orElseThrow();
                unitOfWork.afterCommit(() -> notificationService.notifyTicketClosed(server, closed));
            }
        });
    }

    public void requireNoOtherAppealInProgress(String punishmentId, @Nullable String exceptAppealId) {
        boolean inProgress = ticketRepository.findAppealSummariesByPunishmentId(punishmentId).stream()
            .filter(existing -> !existing.getId().equals(exceptAppealId))
            .anyMatch(existing -> existing.getAppealWorkflowStatus() == null || !existing.getAppealWorkflowStatus().isTerminal());
        if (inProgress) {
            throw new ConflictException("Another appeal for this punishment is already in progress", APPEAL_IN_PROGRESS);
        }
    }

    public void requireReopenable(String appealId, @Nullable String punishmentId, @Nullable AppealWorkflowStatus current,
                                  @Nullable AppealWorkflowStatus next) {
        boolean reopens = next != null && !next.isTerminal() && current != null && current.isTerminal();
        if (reopens && punishmentId != null) {
            requireNoOtherAppealInProgress(punishmentId, appealId);
        }
    }

    public void requireReopenBeforeChangingTerminalStatus(@Nullable AppealWorkflowStatus current,
                                                          @Nullable AppealWorkflowStatus requested) {
        if (current != null && requested != null
            && current.isDecision() && requested.isDecision() && requested != current) {
            throw new ConflictException("Appeal is already " + current.getDisplayName()
                + " and cannot be changed directly to " + requested.getDisplayName()
                + "; reopen the appeal first.");
        }
    }

    public TicketReply statusChangeReply(@Nullable String staffUsername, AppealWorkflowStatus outcome, Instant now) {
        return TicketMessages.systemReply(staffUsername, "Appeal status changed to " + outcome.getDisplayName() + ".",
            "APPEAL_STATUS_" + outcome.name(), now);
    }

    private static boolean isLinkedToPunishment(Ticket appeal, String punishmentId) {
        return punishmentId != null && appeal.appealedPunishmentId().filter(punishmentId::equals).isPresent();
    }
}

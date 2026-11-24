package gg.modl.backend.ai.service;

import gg.modl.backend.ai.data.AIAnalysisResult;
import gg.modl.backend.database.UnitOfWork;
import gg.modl.backend.infrastructure.exception.BaseApplicationException;
import gg.modl.backend.player.dto.request.CreatePunishmentRequest;
import gg.modl.backend.player.repository.PlayerPunishmentLock;
import gg.modl.backend.player.repository.PlayerRepository;
import gg.modl.backend.player.service.PunishmentIssuer;
import gg.modl.backend.player.service.PunishmentLifecycleService;
import gg.modl.backend.server.data.Server;
import gg.modl.backend.settings.data.AIModerationSettings;
import gg.modl.backend.settings.service.AIModerationSettingsService;
import gg.modl.backend.settings.service.AiPunishmentEligibility;
import gg.modl.backend.settings.service.PunishmentTypeService;
import gg.modl.backend.ticket.data.Ticket;
import gg.modl.backend.ticket.data.TicketNote;
import gg.modl.backend.ticket.data.TicketReply;
import gg.modl.backend.ticket.data.TicketStatus;
import gg.modl.backend.ticket.repository.TicketHeaderPatch;
import gg.modl.backend.ticket.repository.TicketRepository;
import gg.modl.backend.ticket.service.TicketMessages;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class AiAnalysisWriter {
    public static final String AI_MODERATOR = "AI Moderator";
    static final double AUTOMATED_ACTION_CONFIDENCE_THRESHOLD = 0.85;
    private static final String CLOSING_REPLY =
        "This report has been reviewed and appropriate action has been taken. Thank you for your report.";

    private final TicketRepository ticketRepository;
    private final AIModerationSettingsService moderationSettings;
    private final AiPunishmentEligibility eligibility;
    private final PunishmentLifecycleService punishmentLifecycleService;
    private final PunishmentTypeService punishmentTypeService;
    private final PlayerRepository playerRepository;
    private final PlayerPunishmentLock playerPunishmentLock;
    private final UnitOfWork unitOfWork;
    private final Clock clock;

    public void record(Server server, String ticketId, AIAnalysisResult result) {
        try {
            unitOfWork.write(() -> store(server, ticketId, result, true));
        } catch (BaseApplicationException | IllegalStateException | IllegalArgumentException refused) {
            log.info("The automated action for ticket {} was refused; keeping the AI suggestion for human review", ticketId);
            unitOfWork.write(() -> store(server, ticketId, result, false));
        }
    }

    public boolean accepts(Server server, AIModerationSettings settings, AIAnalysisResult.SuggestedAction suggestion) {
        return eligibility.accepts(server, settings, suggestion.getPunishmentTypeId(), suggestion.getSeverity());
    }

    public void applyAndClose(Server server, Ticket ticket, AIAnalysisResult analysis, AIAnalysisResult.SuggestedAction suggestion,
                              UUID playerUuid, PunishmentIssuer issuer) {
        String reason = analysis.getAnalysis();
        playerRepository.findByMinecraftUuid(playerUuid.toString())
            .ifPresent(player -> playerPunishmentLock.lock(player.getId()));
        Instant now = clock.instant();
        String ticketId = ticket.getId();
        ticketRepository.updateHeader(ticketId, ticket.getVersion(), TicketHeaderPatch.builder()
            .updatedAt(now)
            .status(TicketStatus.CLOSED)
            .locked(true)
            .aiAnalysis(analysis)
            .build());
        punishmentLifecycleService.createPunishment(server, playerUuid, new CreatePunishmentRequest(
            issuer.name(), issuer.id(), issuer.kind(), suggestion.getPunishmentTypeId(), null, null, List.of(ticket.getId()),
            suggestion.getSeverity(), null, Map.of("aiGenerated", true), reason, null));
        String typeName = punishmentTypeService.getPunishmentTypeName(server, suggestion.getPunishmentTypeId());
        TicketReply systemReply = TicketMessages.systemReply(issuer.name(), CLOSING_REPLY, "Close", now);
        TicketNote staffNote = TicketNote.builder()
            .text("AI Analysis by " + issuer.name() + ": " + typeName + " (" + suggestion.getSeverity() + "). Reason: " + reason)
            .issuerName(issuer.name())
            .date(now)
            .build();
        ticketRepository.appendReply(ticketId, systemReply, now);
        ticketRepository.appendNote(ticketId, staffNote, now);
    }

    public static boolean isHandled(@Nullable AIAnalysisResult analysis) {
        return analysis != null
            && (analysis.isWasAppliedAutomatically() || analysis.isDismissed() || analysis.getAppliedBy() != null);
    }

    public static boolean isClosed(Ticket ticket) {
        return ticket.isLocked() || ticket.getStatus() == TicketStatus.CLOSED;
    }

    public static Optional<UUID> playerUuid(@Nullable String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(UUID.fromString(raw));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    private void store(Server server, String ticketId, AIAnalysisResult result, boolean mayAct) {
        Ticket ticket = ticketRepository.findById(ticketId).orElse(null);
        if (ticket == null || ticket.getAiAnalysis() != null) {
            return;
        }
        AIAnalysisResult analysis = freshCopy(result);
        Optional<UUID> target = playerUuid(ticket.getReportedPlayerUuid());
        Optional<AIAnalysisResult.SuggestedAction> automated = mayAct && target.isPresent()
            ? automatedAction(server, ticket, analysis)
            : Optional.empty();
        if (automated.isPresent()) {
            analysis.setWasAppliedAutomatically(true);
            analysis.setAppliedBy(AI_MODERATOR);
            applyAndClose(server, ticket, analysis, automated.get(), target.get(), PunishmentIssuer.automated(AI_MODERATOR));
            return;
        }
        ticketRepository.updateHeader(ticket.getId(), ticket.getVersion(), TicketHeaderPatch.builder()
            .updatedAt(clock.instant())
            .aiAnalysis(analysis)
            .build());
    }

    private Optional<AIAnalysisResult.SuggestedAction> automatedAction(Server server, Ticket ticket, AIAnalysisResult analysis) {
        AIAnalysisResult.SuggestedAction suggestion = analysis.getSuggestedAction();
        Double confidence = analysis.getConfidence();
        if (suggestion == null || isClosed(ticket) || confidence == null || confidence < AUTOMATED_ACTION_CONFIDENCE_THRESHOLD) {
            return Optional.empty();
        }
        AIModerationSettings settings = moderationSettings.getAIModerationSettings(server);
        boolean accepted = settings.isEnableAIReview() && settings.isEnableAutomatedActions() && accepts(server, settings, suggestion);
        return accepted ? Optional.of(suggestion) : Optional.empty();
    }

    private static AIAnalysisResult freshCopy(AIAnalysisResult result) {
        AIAnalysisResult copy = new AIAnalysisResult(
            result.getAnalysis(), result.getSuggestedAction(), result.getCreatedAt(), result.getRawResponse());
        copy.setConfidence(result.getConfidence());
        return copy;
    }
}

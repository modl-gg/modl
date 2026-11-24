package gg.modl.backend.ai.service;

import gg.modl.backend.ai.data.AIAnalysisResult;
import gg.modl.backend.ai.model.AiCompleter;
import gg.modl.backend.ai.model.AiCompletionRequest;
import gg.modl.backend.ai.model.AiConnection;
import gg.modl.backend.ai.model.AiConnectionState;
import gg.modl.backend.ai.model.AiConnections;
import gg.modl.backend.ai.model.AiDeadline;
import gg.modl.backend.ai.model.AiFailureKind;
import gg.modl.backend.ai.model.AiOutcome;
import gg.modl.backend.ai.model.AiUsageLimits;
import gg.modl.backend.ai.usage.AiConcurrencyLimiter;
import gg.modl.backend.ai.usage.AiRuntimeState;
import gg.modl.backend.ai.usage.AiUsageGuard;
import gg.modl.backend.database.UnitOfWork;
import gg.modl.backend.player.service.IssuerNameResolver;
import gg.modl.backend.player.service.PunishmentIssuer;
import gg.modl.backend.player.service.PunishmentLifecycleService;
import gg.modl.backend.server.data.Server;
import gg.modl.backend.settings.data.AIModerationSettings;
import gg.modl.backend.settings.data.AIModerationSettings.AIPunishmentConfig;
import gg.modl.backend.settings.service.AIModerationSettingsService;
import gg.modl.backend.settings.service.AiPunishmentEligibility;
import gg.modl.backend.ticket.data.Ticket;
import gg.modl.backend.ticket.data.TicketCategory;
import gg.modl.backend.ticket.repository.TicketHeaderPatch;
import gg.modl.backend.ticket.repository.TicketRepository;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class AITicketAnalysisService {
    static final Duration ANALYSIS_DEADLINE = Duration.ofMinutes(2);

    private final AiConnections connections;
    private final AiCompleter completer;
    private final AiUsageGuard usageGuard;
    private final AiRuntimeState runtime;
    private final AIModerationSettingsService moderationSettings;
    private final AiPunishmentEligibility eligibility;
    private final ChatModerationPromptBuilder promptBuilder;
    private final AiAnalysisResponseParser responseParser;
    private final AiAnalysisWriter writer;
    private final TicketRepository ticketRepository;
    private final PunishmentLifecycleService punishmentLifecycleService;
    private final IssuerNameResolver issuerNameResolver;
    private final UnitOfWork unitOfWork;
    private final Clock clock;

    @Async
    public void analyzeTicketAsync(Server server, String ticketId) {
        Optional<Analysis> queued = prepare(server, ticketId);
        if (queued.isEmpty()) {
            return;
        }
        AiUsageGuard.Admission admission = usageGuard.admit(queued.get().reporterUuid(), queued.get().limits());
        if (admission.refusal() != null) {
            skipped(ticketId, queued.get().connection(), admission.refusal());
            return;
        }
        if (admission.permit().isEmpty()) {
            log.debug("Skipping AI analysis of ticket {}: AI analysis is paused", ticketId);
            return;
        }
        AiConcurrencyLimiter.Permit permit = admission.permit().get();
        try {
            prepare(server, ticketId).ifPresent(analysis -> run(server, ticketId, analysis));
        } finally {
            permit.close();
        }
    }

    private void run(Server server, String ticketId, Analysis analysis) {
        AiConnection connection = analysis.connection();
        AiOutcome outcome = completer.complete(connection, analysis.request(), AiDeadline.after(ANALYSIS_DEADLINE),
            usageGuard.meteredSends(analysis.limits()));
        switch (outcome) {
            case AiOutcome.Failed failed -> failed(ticketId, connection, failed);
            case AiOutcome.Completed completed -> completed(server, ticketId, analysis, completed);
        }
    }

    private void completed(Server server, String ticketId, Analysis analysis, AiOutcome.Completed completed) {
        Optional<AIAnalysisResult> result = responseParser.parse(completed.text(), analysis.typeIds(), clock.instant());
        if (result.isEmpty()) {
            runtime.recordFailure(AiFailureKind.INVALID_RESPONSE);
            log.info("AI analysis of ticket {} failed: provider {}, kind {}", ticketId, analysis.connection().provider().id(),
                AiFailureKind.INVALID_RESPONSE);
            return;
        }
        runtime.recordSuccess();
        if (!completed.shape().equals(analysis.connection().shape())) {
            connections.recordShape(analysis.connection(), completed.shape(), null);
        }
        writer.record(server, ticketId, result.get());
    }

    private void failed(String ticketId, AiConnection connection, AiOutcome.Failed failed) {
        runtime.recordFailure(failed.failure().kind());
        log.info("AI analysis of ticket {} failed: provider {}, kind {}, status {}", ticketId, connection.provider().id(),
            failed.failure().kind(), failed.failure().status());
    }

    private void skipped(String ticketId, AiConnection connection, AiFailureKind refusal) {
        runtime.recordFailure(refusal);
        log.info("AI analysis of ticket {} skipped: provider {}, kind {}", ticketId, connection.provider().id(), refusal);
    }

    private Optional<Analysis> prepare(Server server, String ticketId) {
        AiConnectionState state = connections.state();
        Optional<AiConnection> connection = state.ready();
        if (connection.isEmpty()) {
            return Optional.empty();
        }
        AIModerationSettings settings = moderationSettings.getAIModerationSettings(server);
        List<AIPunishmentConfig> offered = settings.isEnableAIReview() ? eligibility.offered(server, settings) : List.of();
        if (offered.isEmpty()) {
            return Optional.empty();
        }
        Optional<Ticket> ticket = unitOfWork.read(() -> ticketRepository.findById(ticketId)).filter(AITicketAnalysisService::analyzable);
        if (ticket.isEmpty()) {
            log.debug("Skipping AI analysis of ticket {}: not an open chat report without an analysis", ticketId);
            return Optional.empty();
        }
        ChatModerationPromptBuilder.ModerationPrompt prompt = promptBuilder.buildModerationPrompt(ticket.get(), offered);
        AiUsageLimits limits = state.limits();
        AiCompletionRequest request = new AiCompletionRequest(prompt.systemInstruction(), prompt.userContent(),
            ChatModerationSchema.forTypes(prompt.typeIds()), limits.maxOutputTokens());
        return Optional.of(new Analysis(connection.get(), request, prompt.typeIds(), limits, ticket.get().getCreatorUuid()));
    }

    private static boolean analyzable(Ticket ticket) {
        return ticket.getType() == TicketCategory.CHAT
            && ticket.getChatMessages() != null && !ticket.getChatMessages().isEmpty()
            && !AiAnalysisWriter.isClosed(ticket)
            && ticket.getAiAnalysis() == null;
    }

    public AISuggestionResult applyAISuggestion(Server server, String ticketId, String actingEmail) {
        return unitOfWork.write(() -> applySuggestion(server, ticketId, actingEmail));
    }

    private AISuggestionResult applySuggestion(Server server, String ticketId, String actingEmail) {
        Ticket ticket = ticketRepository.findById(ticketId).orElse(null);
        if (ticket == null) {
            return new AISuggestionResult(false, "Ticket not found");
        }
        AIAnalysisResult analysis = ticket.getAiAnalysis();
        AIAnalysisResult.SuggestedAction suggestion = analysis == null ? null : analysis.getSuggestedAction();
        if (analysis == null || suggestion == null) {
            return new AISuggestionResult(false, "No AI suggestion to apply");
        }
        if (AiAnalysisWriter.isHandled(analysis)) {
            return new AISuggestionResult(false, "AI suggestion already handled");
        }
        if (AiAnalysisWriter.isClosed(ticket)) {
            return new AISuggestionResult(false, "Ticket already closed");
        }
        Optional<UUID> playerUuid = AiAnalysisWriter.playerUuid(ticket.getReportedPlayerUuid());
        if (playerUuid.isEmpty()) {
            return new AISuggestionResult(false, "No valid reported player UUID");
        }
        if (!writer.accepts(server, moderationSettings.getAIModerationSettings(server), suggestion)) {
            return new AISuggestionResult(false, "The suggested punishment type is not an enabled AI punishment type with configured lengths");
        }
        punishmentLifecycleService.validatePunishmentPermission(server, actingEmail, suggestion.getPunishmentTypeId());
        PunishmentIssuer issuer = issuerNameResolver.panelIssuer(server, actingEmail);
        analysis.setAppliedBy(issuer.name());
        writer.applyAndClose(server, ticket, analysis, suggestion, playerUuid.get(), issuer);
        return new AISuggestionResult(true, null);
    }

    public AISuggestionResult dismissAISuggestion(Server server, String ticketId) {
        return unitOfWork.write(() -> dismiss(ticketId));
    }

    private AISuggestionResult dismiss(String ticketId) {
        Ticket ticket = ticketRepository.findById(ticketId).orElse(null);
        if (ticket == null) {
            return new AISuggestionResult(false, "Ticket not found");
        }
        AIAnalysisResult analysis = ticket.getAiAnalysis();
        if (analysis == null) {
            return new AISuggestionResult(false, "No AI analysis to dismiss");
        }
        if (AiAnalysisWriter.isHandled(analysis)) {
            return new AISuggestionResult(false, "AI suggestion already handled");
        }
        analysis.setDismissed(true);
        ticketRepository.updateHeader(ticket.getId(), ticket.getVersion(), TicketHeaderPatch.builder()
            .updatedAt(clock.instant())
            .aiAnalysis(analysis)
            .build());
        return new AISuggestionResult(true, null);
    }

    public record AISuggestionResult(boolean success, @Nullable String error) {
    }

    private record Analysis(AiConnection connection, AiCompletionRequest request, List<String> typeIds, AiUsageLimits limits,
                            @Nullable String reporterUuid) {
    }
}

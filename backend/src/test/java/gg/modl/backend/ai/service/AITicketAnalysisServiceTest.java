package gg.modl.backend.ai.service;

import static gg.modl.backend.ticket.TicketHarness.NOW;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import gg.modl.backend.ai.data.AIAnalysisResult;
import gg.modl.backend.ai.model.AiCallFailure;
import gg.modl.backend.ai.model.AiCompleter;
import gg.modl.backend.ai.model.AiConnection;
import gg.modl.backend.ai.model.AiConnectionState;
import gg.modl.backend.ai.model.AiConnections;
import gg.modl.backend.ai.model.AiFailureKind;
import gg.modl.backend.ai.model.AiOutcome;
import gg.modl.backend.ai.model.AiRequestShape;
import gg.modl.backend.ai.model.AiUsageLimits;
import gg.modl.backend.ai.provider.AiProviderCatalog;
import gg.modl.backend.ai.usage.AiBudgetLedger;
import gg.modl.backend.ai.usage.AiConcurrencyLimiter;
import gg.modl.backend.ai.usage.AiReporterLimiter;
import gg.modl.backend.ai.usage.AiRuntimeState;
import gg.modl.backend.ai.usage.AiUsageGuard;
import gg.modl.backend.ai.usage.AiUsageCounters;
import gg.modl.backend.player.data.Player;
import gg.modl.backend.player.repository.PlayerPunishmentLock;
import gg.modl.backend.player.repository.PlayerRepository;
import gg.modl.backend.player.service.IssuerNameResolver;
import gg.modl.backend.player.service.PunishmentLifecycleService;
import gg.modl.backend.server.data.Server;
import gg.modl.backend.settings.data.AIModerationSettings;
import gg.modl.backend.settings.data.DefaultPunishmentTypes;
import gg.modl.backend.settings.service.AIModerationSettingsService;
import gg.modl.backend.settings.service.AiPunishmentEligibility;
import gg.modl.backend.settings.service.PunishmentTypeService;
import gg.modl.backend.staff.data.Staff;
import gg.modl.backend.support.TestServers;
import gg.modl.backend.support.sql.DialectTarget;
import gg.modl.backend.support.sql.RetryInjection;
import gg.modl.backend.support.sql.SqlContractTest;
import gg.modl.backend.ticket.TicketHarness;
import gg.modl.backend.ticket.data.Ticket;
import gg.modl.backend.ticket.data.TicketCategory;
import gg.modl.backend.ticket.data.TicketStatus;
import gg.modl.backend.ticket.repository.TicketHeaderPatch;
import java.net.URI;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.mockito.stubbing.Answer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.io.TempDir;

class AITicketAnalysisServiceTest {
    private static final String REPORT = "CHAT-100001";
    private static final String ACTOR = "mod@example.com";
    private static final String REPORTED = "22222222-3333-4444-5555-666666666666";
    private static final int WAIT_SECONDS = 30;
    private static final int MANUAL_BAN = 2;
    private static final int CHAT_ABUSE = 6;
    private static final int TARGETING = 8;

    private final Server server = TestServers.server("server", "admin@example.com");
    private static final AiConnection CONNECTION = new AiConnection(new AiProviderCatalog().find("openai").orElseThrow(),
        URI.create("https://api.openai.com/v1"), "key", "model", null);

    private final AiConnections connections = mock(AiConnections.class);
    private final AiCompleter completer = mock(AiCompleter.class);
    private final AiBudgetLedger ledger = mock(AiBudgetLedger.class);
    private final AIModerationSettingsService settingsService = mock(AIModerationSettingsService.class);
    private final PunishmentLifecycleService lifecycle = mock(PunishmentLifecycleService.class);
    private final PunishmentTypeService types = mock(PunishmentTypeService.class);
    private final PlayerRepository players = mock(PlayerRepository.class);
    private final PlayerPunishmentLock playerLock = mock(PlayerPunishmentLock.class);

    @TempDir
    Path dataDirectory;

    @BeforeEach
    void defaultPunishmentTypes() {
        when(connections.state()).thenReturn(readyState());
        when(ledger.current(any())).thenReturn(AiUsageCounters.NONE);
        when(types.getPunishmentTypeByOrdinal(eq(server), anyInt())).thenAnswer(invocation -> DefaultPunishmentTypes.getAll().stream()
            .filter(type -> type.getOrdinal() == (int) invocation.getArgument(1))
            .findFirst());
    }

    @SqlContractTest
    void aSuggestedCoreTypeIsNeverActedOnBecauseItHasNoConfiguredLength(DialectTarget target) {
        try (TicketHarness harness = chatReport(target, null)) {
            enable();
            answerWith(response("0.95", MANUAL_BAN));

            service(harness).analyzeTicketAsync(server, REPORT);

            assertNeverActedOn(harness);
        }
    }

    @SqlContractTest
    void theWriterRefusesToAutomateACoreTypeEvenWhenTheModelNamesIt(DialectTarget target) {
        try (TicketHarness harness = chatReport(target, null)) {
            enable();

            AIAnalysisResult confident = suggestion(MANUAL_BAN);
            confident.setConfidence(0.99);

            writer(harness).record(server, REPORT, confident);

            assertOnlySuggested(harness);
        }
    }

    @SqlContractTest
    void theModelIsOfferedOnlyTheTypesThatCanTakeItsSuggestion(DialectTarget target) {
        try (TicketHarness harness = chatReport(target, null)) {
            answer("0.10");

            service(harness).analyzeTicketAsync(server, REPORT);

            verify(completer).complete(any(), argThat(request -> request.system().contains("(Chat Abuse)")
                && !request.system().contains("(Manual Ban)")), any(), any());
        }
    }

    @SqlContractTest
    void applyingASuggestedCoreTypeIsRefused(DialectTarget target) {
        try (TicketHarness harness = chatReport(target, suggestion(MANUAL_BAN))) {
            enable();

            assertFalse(service(harness).applyAISuggestion(server, REPORT, ACTOR).success());
            verify(lifecycle, never()).createPunishment(any(), any(), any());
        }
    }

    @SqlContractTest
    void applyingATypeTheAiWasNotConfiguredForIsRefused(DialectTarget target) {
        try (TicketHarness harness = chatReport(target, suggestion(TARGETING))) {
            enable();

            assertFalse(service(harness).applyAISuggestion(server, REPORT, ACTOR).success());
            verify(lifecycle, never()).createPunishment(any(), any(), any());
        }
    }

    @SqlContractTest
    void aConfidentViolationIsActedOnOnce(DialectTarget target) {
        try (TicketHarness harness = chatReport(target, null)) {
            when(players.findByMinecraftUuid(REPORTED)).thenReturn(Optional.of(Player.builder().id("player-1").build()));
            answer("0.95");

            service(harness).analyzeTicketAsync(server, REPORT);

            Ticket stored = harness.stored(REPORT);
            assertEquals(TicketStatus.CLOSED, stored.getStatus());
            assertTrue(stored.isLocked());
            assertTrue(stored.getAiAnalysis().isWasAppliedAutomatically());
            assertEquals("AI Moderator", stored.getAiAnalysis().getAppliedBy());
            assertEquals(1, stored.getReplies().size());
            assertEquals(1, stored.getNotes().size());
            verify(playerLock).lock("player-1");
            verify(lifecycle, times(1)).createPunishment(eq(server), eq(UUID.fromString(REPORTED)), any());
        }
    }

    @SqlContractTest
    void thePromptKeepsChatOutOfTheSystemInstruction(DialectTarget target) {
        try (TicketHarness harness = chatReport(target, null)) {
            answer("0.1");

            service(harness).analyzeTicketAsync(server, REPORT);

            verify(completer).complete(any(), argThat(request -> !request.system().contains("bad chat")
                && request.user().contains("BEGIN_UNTRUSTED_CHAT_DATA")
                && request.user().contains("END_UNTRUSTED_CHAT_DATA") && request.user().contains("bad chat")), any(), any());
        }
    }

    @SqlContractTest
    void confidenceOutsideTheUnitIntervalIsLeftForHumanReview(DialectTarget target) {
        for (String confidence : List.of("85", "5", "1.0001", "-1", "\"NaN\"", "\"Infinity\"", "\"-Infinity\"", "\"0.9\"")) {
            try (TicketHarness harness = chatReport(target, null)) {
                answer(confidence);

                service(harness).analyzeTicketAsync(server, REPORT);

                assertNeverActedOn(harness);
                assertNull(harness.stored(REPORT).getAiAnalysis());
            }
        }
        try (TicketHarness harness = chatReport(target, null)) {
            answer("0.84");

            service(harness).analyzeTicketAsync(server, REPORT);

            assertOnlySuggested(harness);
        }
    }

    @SqlContractTest
    void theBoundsOfTheActingRangeQualify(DialectTarget target) {
        for (String confidence : List.of("0.85", "1.0")) {
            try (TicketHarness harness = chatReport(target, null)) {
                answer(confidence);

                service(harness).analyzeTicketAsync(server, REPORT);

                assertTrue(harness.stored(REPORT).getAiAnalysis().isWasAppliedAutomatically());
            }
        }
        verify(lifecycle, times(2)).createPunishment(any(), any(), any());
    }

    @SqlContractTest
    void aPreviouslyAppliedOrDismissedAnalysisIsNeverActedOnAgain(DialectTarget target) {
        List<Consumer<AIAnalysisResult>> handled = List.of(
            analysis -> analysis.setWasAppliedAutomatically(true),
            analysis -> analysis.setDismissed(true),
            analysis -> analysis.setAppliedBy("Mod"));
        for (Consumer<AIAnalysisResult> handling : handled) {
            AIAnalysisResult previous = suggestion();
            handling.accept(previous);
            try (TicketHarness harness = chatReport(target, previous)) {
                answer("0.99");

                service(harness).analyzeTicketAsync(server, REPORT);

                assertEquals(previous, harness.stored(REPORT).getAiAnalysis());
                assertEquals(TicketStatus.OPEN, harness.stored(REPORT).getStatus());
            }
        }
        verify(lifecycle, never()).createPunishment(any(), any(), any());
        verify(completer, never()).complete(any(), any(), any(), any());
    }

    @SqlContractTest
    void aTicketClosedDuringTheModelCallIsNotActedOn(DialectTarget target) {
        try (TicketHarness harness = chatReport(target, null)) {
            enable();
            answerAfter(() -> harness.tickets().closeIfUnlocked(List.of(REPORT), NOW), response("0.99"));

            service(harness).analyzeTicketAsync(server, REPORT);

            Ticket stored = harness.stored(REPORT);
            assertFalse(stored.getAiAnalysis().isWasAppliedAutomatically());
            assertEquals(List.of(), stored.getReplies());
            verify(lifecycle, never()).createPunishment(any(), any(), any());
        }
    }

    @SqlContractTest
    void aDismissalDuringTheModelCallSurvives(DialectTarget target) {
        try (TicketHarness harness = chatReport(target, null)) {
            enable();
            answerAfter(() -> {
                AIAnalysisResult dismissed = suggestion();
                dismissed.setDismissed(true);
                harness.tickets().updateHeader(REPORT, harness.stored(REPORT).getVersion(),
                    TicketHeaderPatch.builder().updatedAt(NOW).aiAnalysis(dismissed).build());
            }, response("0.99"));

            service(harness).analyzeTicketAsync(server, REPORT);

            assertTrue(harness.stored(REPORT).getAiAnalysis().isDismissed());
            verify(lifecycle, never()).createPunishment(any(), any(), any());
        }
    }

    @SqlContractTest
    void automationSwitchedOffDuringTheModelCallIsNotActedOn(DialectTarget target) {
        try (TicketHarness harness = chatReport(target, null)) {
            when(settingsService.getAIModerationSettings(server)).thenReturn(settings(true, true), settings(false, true));
            answerWith(response("0.99"));

            service(harness).analyzeTicketAsync(server, REPORT);

            assertOnlySuggested(harness);
        }
    }

    @SqlContractTest
    void aTypeDisabledDuringTheModelCallIsNotActedOn(DialectTarget target) {
        try (TicketHarness harness = chatReport(target, null)) {
            when(settingsService.getAIModerationSettings(server))
                .thenReturn(settings(true, true), settings(true, true), settings(true, false));
            answerWith(response("0.99"));

            service(harness).analyzeTicketAsync(server, REPORT);

            assertOnlySuggested(harness);
        }
    }

    @SqlContractTest
    void theModelCallNeverOverwritesAReplyWrittenMeanwhile(DialectTarget target) {
        try (TicketHarness harness = chatReport(target, null)) {
            enable();
            answerAfter(() -> harness.tickets().appendReply(REPORT, TicketHarness.reply("meanwhile").build(), NOW), response("0.2"));

            service(harness).analyzeTicketAsync(server, REPORT);

            Ticket stored = harness.stored(REPORT);
            assertEquals(1, stored.getReplies().size());
            assertNotNull(stored.getAiAnalysis());
        }
    }

    @SqlContractTest
    void twoConcurrentAnalysesPunishOnce(DialectTarget target) throws Exception {
        try (TicketHarness harness = chatReport(target, null)) {
            enable();
            CyclicBarrier bothAnswered = new CyclicBarrier(2);
            when(completer.complete(any(), any(), any(), any())).thenAnswer(invocation -> {
                bothAnswered.await(WAIT_SECONDS, TimeUnit.SECONDS);
                return completed(response("0.99"));
            });
            AITicketAnalysisService service = service(harness);

            ExecutorService threads = Executors.newFixedThreadPool(2);
            try {
                Future<?> first = threads.submit(() -> service.analyzeTicketAsync(server, REPORT));
                Future<?> second = threads.submit(() -> service.analyzeTicketAsync(server, REPORT));
                first.get(WAIT_SECONDS, TimeUnit.SECONDS);
                second.get(WAIT_SECONDS, TimeUnit.SECONDS);
            } finally {
                threads.shutdownNow();
            }

            verify(lifecycle, times(1)).createPunishment(any(), any(), any());
            Ticket stored = harness.stored(REPORT);
            assertEquals(1, stored.getReplies().size());
            assertTrue(stored.getAiAnalysis().isWasAppliedAutomatically());
        }
    }

    @SqlContractTest
    void aFailedPunishmentRollsBackAndKeepsTheSuggestion(DialectTarget target) {
        try (TicketHarness harness = chatReport(target, null)) {
            answer("0.99");
            when(lifecycle.createPunishment(any(), any(), any())).thenThrow(new IllegalStateException("no player"));

            service(harness).analyzeTicketAsync(server, REPORT);

            Ticket stored = harness.stored(REPORT);
            assertEquals(TicketStatus.OPEN, stored.getStatus());
            assertFalse(stored.getAiAnalysis().isWasAppliedAutomatically());
            assertEquals(List.of(), stored.getReplies());
        }
    }

    @SqlContractTest
    void anActionWithoutASeverityIsNotASuggestion(DialectTarget target) {
        try (TicketHarness harness = chatReport(target, null)) {
            enable();
            answerWith("{\"analysis\":\"x\",\"confidence\":0.99,\"punishmentTypeId\":\"6\"}");

            service(harness).analyzeTicketAsync(server, REPORT);

            assertNull(harness.stored(REPORT).getAiAnalysis());
            verify(lifecycle, never()).createPunishment(any(), any(), any());
        }
    }

    @SqlContractTest
    void anUnparsableAnswerStoresNothing(DialectTarget target) {
        try (TicketHarness harness = chatReport(target, null)) {
            enable();
            answerWith("not json at all");

            service(harness).analyzeTicketAsync(server, REPORT);

            assertEquals(0, harness.stored(REPORT).getVersion());
        }
    }

    @SqlContractTest
    void applyingRecordsTheStaffMemberByNameAndIdAndOnlyWorksOnce(DialectTarget target) {
        try (TicketHarness harness = chatReport(target, suggestion())) {
            when(harness.staff().findByEmail(ACTOR))
                .thenReturn(Optional.of(Staff.builder().id("staff-7").username("ServerDerivedStaff").email(ACTOR).build()));
            when(types.getPunishmentTypeName(eq(server), anyInt())).thenReturn("Chat Abuse");
            enable();
            AITicketAnalysisService service = service(harness);

            assertTrue(service.applyAISuggestion(server, REPORT, ACTOR).success());
            assertFalse(service.applyAISuggestion(server, REPORT, ACTOR).success());

            Ticket stored = harness.stored(REPORT);
            assertEquals(TicketStatus.CLOSED, stored.getStatus());
            assertEquals("ServerDerivedStaff", stored.getAiAnalysis().getAppliedBy());
            assertFalse(stored.getAiAnalysis().isWasAppliedAutomatically());
            assertEquals("ServerDerivedStaff", stored.getNotes().getFirst().getIssuerName());
            verify(lifecycle, times(1)).createPunishment(any(), any(),
                argThat(request -> "staff-7".equals(request.issuerId()) && "ServerDerivedStaff".equals(request.issuerName())));
        }
    }

    @SqlContractTest
    void applyingOnAClosedTicketIsRefused(DialectTarget target) {
        try (TicketHarness harness = chatReport(target, suggestion())) {
            harness.tickets().closeIfUnlocked(List.of(REPORT), NOW);

            assertFalse(service(harness).applyAISuggestion(server, REPORT, ACTOR).success());
            verify(lifecycle, never()).createPunishment(any(), any(), any());
        }
    }

    @SqlContractTest
    void applyingWithoutAValidPlayerUuidIsRefused(DialectTarget target) {
        try (TicketHarness harness = TicketHarness.start(target, dataDirectory.resolve(UUID.randomUUID().toString()))) {
            harness.insert(TicketHarness.ticket(REPORT).reportedPlayerUuid("not-a-uuid").aiAnalysis(suggestion()));

            AITicketAnalysisService.AISuggestionResult result = service(harness).applyAISuggestion(server, REPORT, ACTOR);

            assertFalse(result.success());
            assertTrue(result.error().contains("UUID"));
        }
    }

    @SqlContractTest
    void dismissingMarksTheAnalysis(DialectTarget target) {
        try (TicketHarness harness = chatReport(target, suggestion())) {
            assertTrue(service(harness).dismissAISuggestion(server, REPORT).success());

            assertTrue(harness.stored(REPORT).getAiAnalysis().isDismissed());
        }
    }

    @SqlContractTest
    void anAnalysisThatWasAlreadyAppliedCannotBeDismissed(DialectTarget target) {
        AIAnalysisResult applied = suggestion();
        applied.setWasAppliedAutomatically(true);
        applied.setAppliedBy(AiAnalysisWriter.AI_MODERATOR);
        try (TicketHarness harness = chatReport(target, applied)) {
            AITicketAnalysisService.AISuggestionResult result = service(harness).dismissAISuggestion(server, REPORT);

            assertFalse(result.success());
            assertFalse(harness.stored(REPORT).getAiAnalysis().isDismissed());
        }
    }

    @SqlContractTest
    void nothingIsSentWhenTheConnectionIsNotReady(DialectTarget target) {
        try (TicketHarness harness = chatReport(target, null)) {
            enable();
            when(connections.state()).thenReturn(new AiConnectionState(AiConnectionState.Readiness.MODEL_MISSING, CONNECTION.provider(),
                Optional.of(CONNECTION), AiConnectionState.Managed.NONE, AiUsageLimits.DEFAULTS));

            service(harness).analyzeTicketAsync(server, REPORT);

            verify(completer, never()).complete(any(), any(), any(), any());
        }
    }

    @SqlContractTest
    void nothingIsSentWhenReviewIsOffOrNoTypeCanTakeASuggestion(DialectTarget target) {
        try (TicketHarness harness = chatReport(target, null)) {
            when(settingsService.getAIModerationSettings(server)).thenReturn(AIModerationSettings.builder().enableAIReview(false)
                .aiPunishmentConfigs(Map.of(String.valueOf(CHAT_ABUSE), config(CHAT_ABUSE, "Chat Abuse", true))).build());
            service(harness).analyzeTicketAsync(server, REPORT);

            when(settingsService.getAIModerationSettings(server)).thenReturn(AIModerationSettings.builder().enableAIReview(true)
                .aiPunishmentConfigs(Map.of(String.valueOf(MANUAL_BAN), config(MANUAL_BAN, "Manual Ban", true))).build());
            service(harness).analyzeTicketAsync(server, REPORT);

            verify(completer, never()).complete(any(), any(), any(), any());
        }
    }

    @SqlContractTest
    void aFailedOutcomeIsRecordedAndNeverParsed(DialectTarget target) {
        try (TicketHarness harness = chatReport(target, null)) {
            enable();
            when(completer.complete(any(), any(), any(), any()))
                .thenReturn(new AiOutcome.Failed(AiCallFailure.of(AiFailureKind.TRUNCATED), CONNECTION.provider().initialShape()));
            AiRuntimeState runtime = runtime();

            service(harness, runtime).analyzeTicketAsync(server, REPORT);

            assertEquals(0, harness.stored(REPORT).getVersion());
            assertEquals(AiFailureKind.TRUNCATED, runtime.view().lastFailure());
        }
    }

    @SqlContractTest
    void aReporterOverTheLimitIsSkippedBeforeAnySend(DialectTarget target) {
        try (TicketHarness harness = chatReport(target, null)) {
            enable();
            answer("0.1");
            when(connections.state()).thenReturn(new AiConnectionState(AiConnectionState.Readiness.READY, CONNECTION.provider(),
                Optional.of(CONNECTION), AiConnectionState.Managed.NONE, new AiUsageLimits(60, 500, 1, 2, 4096)));
            AiRuntimeState runtime = runtime();
            AITicketAnalysisService service = service(harness, runtime);

            service.analyzeTicketAsync(server, REPORT);
            harness.insert(TicketHarness.ticket("CHAT-100002").type(TicketCategory.CHAT).reportedPlayerUuid(REPORTED)
                .chatMessages(List.of(new Ticket.ChatMessage("more", NOW, "Player"))));
            service.analyzeTicketAsync(server, "CHAT-100002");

            verify(completer, times(1)).complete(any(), any(), any(), any());
            assertEquals(AiFailureKind.REPORTER_LIMIT, runtime.view().lastFailure());
        }
    }

    @SqlContractTest
    void aNewlyNegotiatedShapeIsRecordedForTheConnection(DialectTarget target) {
        try (TicketHarness harness = chatReport(target, null)) {
            enable();
            AiRequestShape lowered = CONNECTION.provider().initialShape().withOutputMode(AiRequestShape.OutputMode.JSON_OBJECT);
            when(completer.complete(any(), any(), any(), any())).thenReturn(new AiOutcome.Completed(response("0.1"), lowered));

            service(harness).analyzeTicketAsync(server, REPORT);

            verify(connections).recordShape(CONNECTION.withShape(CONNECTION.provider().initialShape()), lowered, null);
        }
    }

    @SqlContractTest
    void anAutomatedActionSurvivesAnInjectedRetry(DialectTarget target) {
        try (TicketHarness harness = TicketHarness.start(target, dataDirectory.resolve(UUID.randomUUID().toString()), new RetryInjection())) {
            harness.insert(TicketHarness.ticket(REPORT).type(TicketCategory.CHAT).reportedPlayer("Player").reportedPlayerUuid(REPORTED)
                .chatMessages(List.of(new Ticket.ChatMessage("bad chat", NOW, "Player"))));
            answer("0.95");

            service(harness).analyzeTicketAsync(server, REPORT);

            Ticket stored = harness.stored(REPORT);
            assertTrue(stored.getAiAnalysis().isWasAppliedAutomatically());
            assertEquals(1, stored.getReplies().size());
            assertEquals(1, stored.getNotes().size());
        }
    }

    @SqlContractTest
    void theTicketIsAlreadyClosedWhenThePunishmentClosesItsLinkedTickets(DialectTarget target) {
        try (TicketHarness harness = chatReport(target, null)) {
            answer("0.95");
            when(lifecycle.createPunishment(any(), any(), any())).thenAnswer(invocation -> {
                assertEquals(0, harness.tickets().closeIfUnlocked(List.of(REPORT), NOW));
                return "punishment-1";
            });

            service(harness).analyzeTicketAsync(server, REPORT);

            assertEquals(1, harness.stored(REPORT).getReplies().size());
        }
    }

    private void assertNeverActedOn(TicketHarness harness) {
        Ticket stored = harness.stored(REPORT);
        assertTrue(stored.getAiAnalysis() == null || !stored.getAiAnalysis().isWasAppliedAutomatically());
        assertEquals(TicketStatus.OPEN, stored.getStatus());
        verify(lifecycle, never()).createPunishment(any(), any(), any());
    }

    private void assertOnlySuggested(TicketHarness harness) {
        Ticket stored = harness.stored(REPORT);
        assertNotNull(stored.getAiAnalysis());
        assertFalse(stored.getAiAnalysis().isWasAppliedAutomatically());
        assertEquals(TicketStatus.OPEN, stored.getStatus());
        verify(lifecycle, never()).createPunishment(any(), any(), any());
    }

    private TicketHarness chatReport(DialectTarget target, AIAnalysisResult analysis) {
        TicketHarness harness = TicketHarness.start(target, dataDirectory.resolve(UUID.randomUUID().toString()));
        harness.insert(TicketHarness.ticket(REPORT)
            .type(TicketCategory.CHAT)
            .reportedPlayer("Player")
            .reportedPlayerUuid(REPORTED)
            .aiAnalysis(analysis)
            .chatMessages(List.of(new Ticket.ChatMessage("bad chat", NOW, "Player"))));
        return harness;
    }

    private void answer(String confidence) {
        enable();
        answerWith(response(confidence));
    }

    private void answerWith(String text) {
        when(completer.complete(any(), any(), any(), any())).thenReturn(completed(text));
    }

    private void answerAfter(Runnable meanwhile, String text) {
        Answer<AiOutcome> answer = invocation -> {
            meanwhile.run();
            return completed(text);
        };
        when(completer.complete(any(), any(), any(), any())).thenAnswer(answer);
    }

    private static AiOutcome completed(String text) {
        return new AiOutcome.Completed(text, CONNECTION.provider().initialShape());
    }

    private static AiConnectionState readyState() {
        return new AiConnectionState(AiConnectionState.Readiness.READY, CONNECTION.provider(),
            Optional.of(CONNECTION.withShape(CONNECTION.provider().initialShape())), AiConnectionState.Managed.NONE,
            AiUsageLimits.DEFAULTS);
    }

    private void enable() {
        when(settingsService.getAIModerationSettings(server)).thenReturn(settings(true, true));
    }

    private static AIModerationSettings settings(boolean automatedActions, boolean typeEnabled) {
        return AIModerationSettings.builder()
            .enableAIReview(true)
            .enableAutomatedActions(automatedActions)
            .aiPunishmentConfigs(Map.of(
                String.valueOf(CHAT_ABUSE), config(CHAT_ABUSE, "Chat Abuse", typeEnabled),
                String.valueOf(MANUAL_BAN), config(MANUAL_BAN, "Manual Ban", true)))
            .build();
    }

    private static String response(String confidence) {
        return response(confidence, CHAT_ABUSE);
    }

    private static String response(String confidence, int typeOrdinal) {
        return "{\"analysis\":\"violation\",\"confidence\":" + confidence + ",\"punishmentTypeId\":\"" + typeOrdinal
            + "\",\"severity\":\"severe\"}";
    }

    private static AIModerationSettings.AIPunishmentConfig config(int ordinal, String name, boolean enabled) {
        return AIModerationSettings.AIPunishmentConfig.builder().id(String.valueOf(ordinal)).name(name).enabled(enabled).build();
    }

    private static AIAnalysisResult suggestion() {
        return suggestion(CHAT_ABUSE);
    }

    private static AIAnalysisResult suggestion(int typeOrdinal) {
        return new AIAnalysisResult("violation", new AIAnalysisResult.SuggestedAction(typeOrdinal, "severe"), NOW, "{}");
    }

    private AITicketAnalysisService service(TicketHarness harness) {
        return service(harness, runtime());
    }

    private AITicketAnalysisService service(TicketHarness harness, AiRuntimeState runtime) {
        Clock clock = harness.database().clock();
        AiUsageGuard guard = new AiUsageGuard(runtime, new AiReporterLimiter(), new AiConcurrencyLimiter(), ledger, clock);
        return new AITicketAnalysisService(connections, completer, guard, runtime, settingsService, new AiPunishmentEligibility(types),
            new ChatModerationPromptBuilder(), new AiAnalysisResponseParser(), writer(harness), harness.tickets(), lifecycle,
            new IssuerNameResolver(harness.staff()), harness.database().unitOfWork(), clock);
    }

    private AiAnalysisWriter writer(TicketHarness harness) {
        return new AiAnalysisWriter(harness.tickets(), settingsService, new AiPunishmentEligibility(types), lifecycle, types, players,
            playerLock, harness.database().unitOfWork(), harness.database().clock());
    }

    private AiRuntimeState runtime() {
        return new AiRuntimeState(ledger, Clock.systemUTC());
    }
}

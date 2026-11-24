package gg.modl.backend.ticket.repository;

import static gg.modl.backend.database.jooq.Tables.TICKETS;
import static gg.modl.backend.database.jooq.Tables.TICKET_ASSIGNEES;
import static gg.modl.backend.database.jooq.Tables.TICKET_NOTES;
import static gg.modl.backend.database.jooq.Tables.TICKET_REPLIES;
import static gg.modl.backend.database.jooq.Tables.TICKET_TAGS;
import static gg.modl.backend.ticket.TicketHarness.NOW;
import static gg.modl.backend.ticket.TicketHarness.reply;
import static gg.modl.backend.ticket.TicketHarness.ticket;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import gg.modl.backend.ai.data.AIAnalysisResult;
import gg.modl.backend.database.ConcurrentUpdateException;
import gg.modl.backend.database.ReferenceViolationException;
import gg.modl.backend.database.ValueTooLongException;
import gg.modl.backend.support.sql.DialectTarget;
import gg.modl.backend.support.sql.SqlContractTest;
import gg.modl.backend.ticket.TicketHarness;
import gg.modl.backend.ticket.data.AppealWorkflowStatus;
import gg.modl.backend.ticket.data.Ticket;
import gg.modl.backend.ticket.data.TicketCategory;
import gg.modl.backend.ticket.data.TicketNote;
import gg.modl.backend.ticket.data.TicketPriority;
import gg.modl.backend.ticket.data.TicketReply;
import gg.modl.backend.ticket.data.TicketStatus;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.io.TempDir;

class TicketRepositoryContractTest {
    private static final String CREATOR = "11111111-2222-3333-4444-555555555555";
    private static final String REPORTED = "22222222-3333-4444-5555-666666666666";
    private static final Instant LATER = NOW.plusSeconds(60);
    private static final int THREADS = 8;
    private static final long APPEND_SECONDS = 60;

    @TempDir
    Path dataDirectory;

    @SqlContractTest
    void aFullTicketRoundTrips(DialectTarget target) {
        try (TicketHarness harness = TicketHarness.start(target, dataDirectory)) {
            AIAnalysisResult analysis = new AIAnalysisResult(
                "harassment", new AIAnalysisResult.SuggestedAction(7, "severe"), NOW, "{\"raw\":true}");
            analysis.setConfidence(0.9);
            analysis.setAppliedBy("Mod");
            Map<String, Object> data = Map.of("punishmentId", "ABCDEFGH", "a.b", Map.of("$c", List.of("ü", "😀")));
            harness.insert(ticket("APPEAL-100001")
                .type(TicketCategory.APPEAL)
                .status(TicketStatus.CLOSED)
                .locked(true)
                .hidden(true)
                .emailAuthEnabled(true)
                .priority(TicketPriority.HIGH)
                .appealWorkflowStatus(AppealWorkflowStatus.APPROVED)
                .creatorUuid(CREATOR.toUpperCase(Locale.ROOT))
                .creatorName("Creator")
                .creatorAvatar("https://cdn.example/avatar.png")
                .reportedPlayer("Reported")
                .reportedPlayerUuid(REPORTED)
                .replayUrl("https://replays.example/?id=replay-1")
                .replayId("replay-1")
                .tags(List.of("vip", "bug", "vip"))
                .assignedTo(List.of("zed", "amy"))
                .formData(Map.of("$field.one", "value"))
                .data(data)
                .chatMessages(List.of(new Ticket.ChatMessage("hello", NOW, "Reported"), new Ticket.ChatMessage("plain", NOW, null)))
                .aiAnalysis(analysis)
                .replies(List.of(
                    reply("r1").creatorIdentifier("browser").attachments(List.of(Map.of("url", "/v1/storage/objects/a"))).build(),
                    reply("r2").staff(true).action("close").avatar("avatar").build()))
                .notes(List.of(TicketNote.builder().text("note").issuerName("Mod").issuerAvatar("avatar").date(NOW)
                    .attachments(List.of(Map.of("url", "/v1/storage/objects/b", "fileName", "b.png"))).build())));

            Ticket stored = harness.stored("APPEAL-100001");

            assertEquals(TicketCategory.APPEAL, stored.getType());
            assertEquals(TicketStatus.CLOSED, stored.getStatus());
            assertTrue(stored.isLocked());
            assertTrue(stored.isHidden());
            assertTrue(stored.isEmailAuthEnabled());
            assertEquals(TicketPriority.HIGH, stored.getPriority());
            assertEquals(AppealWorkflowStatus.APPROVED, stored.getAppealWorkflowStatus());
            assertEquals(CREATOR, stored.getCreatorUuid());
            assertEquals("Creator", stored.getCreatorName());
            assertEquals("https://cdn.example/avatar.png", stored.getCreatorAvatar());
            assertEquals("Reported", stored.getReportedPlayer());
            assertEquals(REPORTED, stored.getReportedPlayerUuid());
            assertEquals("replay-1", stored.getReplayId());
            assertEquals("https://replays.example/?id=replay-1", stored.getReplayUrl());
            assertEquals(TicketHarness.ACCESS_KEY, stored.getPublicAccessKey());
            assertEquals(List.of("bug", "vip"), stored.getTags());
            assertEquals(List.of("amy", "zed"), stored.getAssignedTo());
            assertEquals(Map.of("$field.one", "value"), stored.getFormData());
            assertEquals(data, stored.getData());
            assertEquals(List.of(new Ticket.ChatMessage("hello", NOW, "Reported"), new Ticket.ChatMessage("plain", NOW, null)),
                stored.getChatMessages());
            assertEquals(analysis, stored.getAiAnalysis());
            assertEquals(List.of("r1", "r2"), stored.getReplies().stream().map(TicketReply::getId).toList());
            assertEquals("browser", stored.getReplies().getFirst().getCreatorIdentifier());
            assertEquals(List.of(Map.of("url", "/v1/storage/objects/a")), stored.getReplies().getFirst().getAttachments());
            assertTrue(stored.getReplies().getLast().isStaff());
            assertEquals("close", stored.getReplies().getLast().getAction());
            assertEquals("avatar", stored.getReplies().getLast().getAvatar());
            assertEquals("note", stored.getNotes().getFirst().getText());
            assertEquals(List.of(Map.of("url", "/v1/storage/objects/b", "fileName", "b.png")),
                stored.getNotes().getFirst().getAttachments());
            assertEquals(NOW, stored.getCreated());
            assertEquals(0, stored.getVersion());
            assertEquals(2, replyCount(harness, "APPEAL-100001"));
            assertEquals(List.of("APPEAL-100001"), ids(harness.tickets().findAppealsByPunishmentId("ABCDEFGH")));
        }
    }

    @SqlContractTest
    void absentValuesStayAbsent(DialectTarget target) {
        try (TicketHarness harness = TicketHarness.start(target, dataDirectory)) {
            harness.insert(ticket("SUPPORT-100001"));

            Ticket stored = harness.stored("SUPPORT-100001");

            assertNull(stored.getFormData());
            assertNull(stored.getData());
            assertNull(stored.getChatMessages());
            assertNull(stored.getAiAnalysis());
            assertNull(stored.getAppealWorkflowStatus());
            assertEquals(TicketPriority.NORMAL, stored.getPriority());
            assertEquals(List.of(), stored.getTags());
            assertEquals(List.of(), stored.getReplies());
            assertEquals(List.of(), stored.getNotes());
        }
    }

    @SqlContractTest
    void anExistingIdIsReportedAndLeftUntouched(DialectTarget target) {
        try (TicketHarness harness = TicketHarness.start(target, dataDirectory)) {
            harness.insert(ticket("SUPPORT-100001"));

            assertFalse(harness.tickets().insertIfAbsent(ticket("SUPPORT-100001").subject("other").tags(List.of("intruder"))
                .replies(List.of(reply("intruder").build())).build()));

            Ticket stored = harness.stored("SUPPORT-100001");
            assertEquals("Need help", stored.getSubject());
            assertEquals(List.of(), stored.getTags());
            assertEquals(List.of(), stored.getReplies());
        }
    }

    @SqlContractTest
    void concurrentRepliesAndNotesOnOneTicketAreAllStoredWithoutRetries(DialectTarget target) throws Exception {
        AtomicInteger retried = new AtomicInteger();
        try (TicketHarness harness = TicketHarness.start(target, dataDirectory, attempt -> {
            if (attempt > 1) {
                retried.incrementAndGet();
            }
        })) {
            harness.insert(ticket("SUPPORT-100001").replies(List.of(reply("first").build())));

            CountDownLatch start = new CountDownLatch(1);
            ExecutorService threads = Executors.newFixedThreadPool(THREADS);
            try {
                List<Future<?>> appenders = new ArrayList<>();
                for (int thread = 0; thread < THREADS; thread++) {
                    int index = thread;
                    appenders.add(threads.submit(() -> {
                        start.await();
                        harness.tickets().appendReply("SUPPORT-100001", reply("reply-" + index).build(), LATER);
                        harness.tickets().appendNote("SUPPORT-100001",
                            TicketNote.builder().text("note-" + index).date(LATER).build(), LATER);
                        return null;
                    }));
                }
                start.countDown();
                for (Future<?> appender : appenders) {
                    appender.get(APPEND_SECONDS, TimeUnit.SECONDS);
                }
            } finally {
                threads.shutdownNow();
            }

            assertEquals(IntStream.rangeClosed(1, THREADS + 1).boxed().toList(), harness.database().dsl()
                .select(TICKET_REPLIES.SEQ).from(TICKET_REPLIES).orderBy(TICKET_REPLIES.SEQ).fetch(TICKET_REPLIES.SEQ));
            assertEquals(IntStream.rangeClosed(1, THREADS).boxed().toList(), harness.database().dsl()
                .select(TICKET_NOTES.SEQ).from(TICKET_NOTES).orderBy(TICKET_NOTES.SEQ).fetch(TICKET_NOTES.SEQ));
            assertEquals(THREADS + 1, replyCount(harness, "SUPPORT-100001"));
            assertEquals(LATER, harness.stored("SUPPORT-100001").getUpdatedAt());
            assertEquals(0, retried.get());
        }
    }

    @SqlContractTest
    void appendingToAMissingTicketIsAReferenceViolation(DialectTarget target) {
        try (TicketHarness harness = TicketHarness.start(target, dataDirectory)) {
            assertThrows(ReferenceViolationException.class,
                () -> harness.tickets().appendReply("SUPPORT-404", reply("r").build(), NOW));
            assertThrows(ReferenceViolationException.class,
                () -> harness.tickets().appendNote("SUPPORT-404", TicketNote.builder().text("n").build(), NOW));
        }
    }

    @SqlContractTest
    void aHeaderUpdateChangesOnlyWhatThePatchNames(DialectTarget target) {
        try (TicketHarness harness = TicketHarness.start(target, dataDirectory)) {
            harness.insert(ticket("APPEAL-1").type(TicketCategory.APPEAL).creatorName("Old").data(Map.of("punishmentId", "OLD")));

            harness.tickets().updateHeader("APPEAL-1", 0, TicketHeaderPatch.builder()
                .updatedAt(LATER)
                .status(TicketStatus.CLOSED)
                .locked(true)
                .data(Map.of("punishmentId", "NEW"))
                .build());

            Ticket stored = harness.stored("APPEAL-1");
            assertEquals(1, stored.getVersion());
            assertEquals(LATER, stored.getUpdatedAt());
            assertEquals(TicketStatus.CLOSED, stored.getStatus());
            assertTrue(stored.isLocked());
            assertEquals("Old", stored.getCreatorName());
            assertEquals("Need help", stored.getSubject());
            assertEquals(List.of(), harness.tickets().findAppealsByPunishmentId("OLD"));
            assertEquals(List.of("APPEAL-1"), ids(harness.tickets().findAppealsByPunishmentId("NEW")));
        }
    }

    @SqlContractTest
    void aStaleVersionNeverOverwrites(DialectTarget target) {
        try (TicketHarness harness = TicketHarness.start(target, dataDirectory)) {
            harness.insert(ticket("SUPPORT-1"));
            harness.tickets().updateHeader("SUPPORT-1", 0, TicketHeaderPatch.builder().updatedAt(LATER).subject("first").build());

            assertThrows(ConcurrentUpdateException.class, () -> harness.tickets()
                .updateHeader("SUPPORT-1", 0, TicketHeaderPatch.builder().updatedAt(LATER).subject("second").build()));

            assertEquals("first", harness.stored("SUPPORT-1").getSubject());
        }
    }

    @SqlContractTest
    void closeIfUnlockedClosesEachTicketOnce(DialectTarget target) {
        try (TicketHarness harness = TicketHarness.start(target, dataDirectory)) {
            harness.insert(ticket("A-1"), ticket("A-2"), ticket("A-3").status(TicketStatus.CLOSED).locked(true));
            List<String> ids = List.of("A-3", "A-2", "A-1", "A-404");

            assertEquals(2, harness.tickets().closeIfUnlocked(ids, LATER));
            assertEquals(0, harness.tickets().closeIfUnlocked(ids, LATER));

            assertEquals(TicketStatus.CLOSED, harness.stored("A-1").getStatus());
            assertEquals(1, harness.stored("A-1").getVersion());
            assertEquals(0, harness.stored("A-3").getVersion());
        }
    }

    @SqlContractTest
    void tagsAndAssigneesAreSetsThatCanBeCleared(DialectTarget target) {
        try (TicketHarness harness = TicketHarness.start(target, dataDirectory)) {
            harness.insert(ticket("A-1").tags(List.of("bug")).assignedTo(List.of("amy")));

            harness.tickets().addTags("A-1", List.of("vip ", "bug", "vip", " "), LATER);
            assertEquals(List.of("bug", "vip"), harness.stored("A-1").getTags());

            harness.tickets().removeTags("A-1", List.of("bug", "x".repeat(200)), LATER);
            assertEquals(List.of("vip"), harness.stored("A-1").getTags());

            String longest = "n".repeat(256);
            harness.tickets().replaceAssignees("A-1", List.of(longest, "bob", "bob"), LATER);
            assertEquals(List.of("bob", longest), harness.stored("A-1").getAssignedTo());

            harness.tickets().replaceAssignees("A-1", List.of(), LATER);
            assertEquals(List.of(), harness.stored("A-1").getAssignedTo());
            assertEquals(LATER, harness.stored("A-1").getUpdatedAt());
        }
    }

    @SqlContractTest
    void reassigningRepliesTouchesOnlyTheNamedRowsOfThatTicket(DialectTarget target) {
        try (TicketHarness harness = TicketHarness.start(target, dataDirectory)) {
            harness.insert(
                ticket("A-1").replies(List.of(reply("same").build(), reply("kept").build())),
                ticket("A-2").replies(List.of(reply("same").build())));

            harness.tickets().reassignReplies("A-1", List.of("same"), "Claimer", CREATOR);

            List<TicketReply> replies = harness.stored("A-1").getReplies();
            assertEquals("Claimer", replies.getFirst().getName());
            assertEquals(CREATOR, replies.getFirst().getCreatorIdentifier());
            assertEquals("Player", replies.getLast().getName());
            assertEquals("Player", harness.stored("A-2").getReplies().getFirst().getName());
            assertEquals(List.of("claimer", "player"), harness.database().dsl().select(TICKET_REPLIES.AUTHOR_NAME_LOWER)
                .from(TICKET_REPLIES).where(TICKET_REPLIES.TICKET_ID.eq("A-1"))
                .orderBy(TICKET_REPLIES.SEQ).fetch(TICKET_REPLIES.AUTHOR_NAME_LOWER));
        }
    }

    @SqlContractTest
    void lookupValuesThatCannotBeStoredMatchNothing(DialectTarget target) {
        try (TicketHarness harness = TicketHarness.start(target, dataDirectory)) {
            harness.insert(ticket("A-1").tags(List.of("bug")).assignedTo(List.of("amy")));
            String tooLong = "x".repeat(600);

            assertTrue(harness.tickets().findById(tooLong).isEmpty());
            assertEquals(List.of(), harness.tickets().findListedByIds(List.of(tooLong)));
            assertEquals(List.of(), harness.tickets().findByTag(tooLong));
            assertEquals(List.of(), harness.tickets().findByPlayer(tooLong));
            assertEquals(List.of(), harness.tickets().findRecentAssignedWithReplies(tooLong, 10));
            assertEquals(0, harness.tickets().search(filter(null, null, List.of(), null, List.of(tooLong), List.of()),
                TicketSortOption.NEWEST, 1, 10).total());
        }
    }

    @SqlContractTest
    void columnLengthsCountCodePoints(DialectTarget target) {
        try (TicketHarness harness = TicketHarness.start(target, dataDirectory)) {
            String astral = "😀".repeat(100);
            harness.insert(ticket("A-1").creatorName(astral));

            assertEquals(astral, harness.stored("A-1").getCreatorName());
            assertThrows(ValueTooLongException.class,
                () -> harness.tickets().insertIfAbsent(ticket("A-2").creatorName(astral + "x").build()));
            assertThrows(ValueTooLongException.class,
                () -> harness.tickets().insertIfAbsent(ticket("x".repeat(65)).build()));
            assertThrows(ValueTooLongException.class,
                () -> harness.tickets().insertIfAbsent(ticket("A-3").tags(List.of("x".repeat(65))).build()));
            assertThrows(ValueTooLongException.class,
                () -> harness.tickets().appendReply("A-1", reply("r").type("x".repeat(33)).build(), NOW));
            assertThrows(ValueTooLongException.class,
                () -> harness.tickets().appendReply("A-1", reply("r").action("x".repeat(65)).build(), NOW));
            assertThrows(ValueTooLongException.class,
                () -> harness.tickets().appendReply("A-1", reply("r").creatorIdentifier("x".repeat(257)).build(), NOW));
        }
    }

    @SqlContractTest
    void referencesTooLongForTheirColumnAreStoredAsNoReference(DialectTarget target) {
        try (TicketHarness harness = TicketHarness.start(target, dataDirectory)) {
            harness.insert(ticket("A-1").replayId("r".repeat(65)).replayUrl("r".repeat(65)));

            assertNull(harness.stored("A-1").getReplayId());
            assertEquals("r".repeat(65), harness.stored("A-1").getReplayUrl());
        }
    }

    @SqlContractTest
    void searchAppliesEveryFilter(DialectTarget target) {
        try (TicketHarness harness = TicketHarness.start(target, dataDirectory)) {
            harness.insert(
                ticket("BUG-1").type(TicketCategory.BUG).creatorName("Alice").tags(List.of("a", "b")).assignedTo(List.of("amy")),
                ticket("PLAYER-1").type(TicketCategory.PLAYER).creatorName("Bob").tags(List.of("a"))
                    .replies(List.of(reply("r").content("hidden needle").build())),
                ticket("CHAT-1").type(TicketCategory.CHAT).status(TicketStatus.CLOSED).locked(true),
                ticket("SUPPORT-1").status(TicketStatus.UNFINISHED));

            assertEquals(Set.of("BUG-1", "PLAYER-1", "CHAT-1"), found(harness, filter(null, null, List.of(), null, List.of(), List.of())));
            assertEquals(Set.of("BUG-1", "PLAYER-1"), found(harness, filter(null, "open", List.of(), null, List.of(), List.of())));
            assertEquals(Set.of("CHAT-1"), found(harness, filter(null, "closed", List.of(), null, List.of(), List.of())));
            assertEquals(Set.of("SUPPORT-1"), found(harness, filter(null, "unfinished", List.of(), null, List.of(), List.of())));
            assertEquals(Set.of("PLAYER-1", "CHAT-1"), found(harness, filter(null, null, List.of("report"), null, List.of(), List.of())));
            assertEquals(Set.of("BUG-1"), found(harness, filter(null, null, List.of("bug_report"), null, List.of(), List.of())));
            assertEquals(Set.of(), found(harness, filter(null, null, List.of("no such type"), null, List.of(), List.of())));
            assertEquals(Set.of("BUG-1"), found(harness, filter(null, null, List.of(), "LIC", List.of(), List.of())));
            assertEquals(Set.of("BUG-1"), found(harness, filter(null, null, List.of(), null, List.of("a", "b"), List.of())));
            assertEquals(Set.of("BUG-1"), found(harness, filter(null, null, List.of(), null, List.of(), List.of(" AMY "))));
            assertEquals(Set.of("PLAYER-1", "CHAT-1"), found(harness, filter(null, null, List.of(), null, List.of(), List.of("none"))));
            assertEquals(Set.of("PLAYER-1"), found(harness, filter("NEEDLE", null, List.of(), null, List.of(), List.of())));
            assertEquals(Set.of("BUG-1"), found(harness, filter("bug-", null, List.of(), null, List.of(), List.of())));

            assertEquals(new TicketCounts(2, 1), harness.tickets().count(filter(null, null, List.of(), null, List.of(), List.of())));
            assertEquals(new TicketCounts(0, 0), harness.tickets().count(filter("needle", null, List.of(), null, List.of(), List.of())));
        }
    }

    @SqlContractTest
    void searchWildcardsAreLiteral(DialectTarget target) {
        try (TicketHarness harness = TicketHarness.start(target, dataDirectory)) {
            harness.insert(ticket("A-1").subject("100% broken"), ticket("A-2").subject("snake_case"),
                ticket("A-3").subject("help!"), ticket("A-4").subject("plain"));

            assertEquals(Set.of("A-1"), found(harness, filter("%", null, List.of(), null, List.of(), List.of())));
            assertEquals(Set.of("A-2"), found(harness, filter("_", null, List.of(), null, List.of(), List.of())));
            assertEquals(Set.of("A-3"), found(harness, filter("!", null, List.of(), null, List.of(), List.of())));
        }
    }

    @SqlContractTest
    void searchFoldsCaseAsEachDialectDocuments(DialectTarget target) {
        try (TicketHarness harness = TicketHarness.start(target, dataDirectory)) {
            harness.insert(ticket("A-1").subject("été"));

            Set<String> expected = target.dialect().isMysqlFamily() ? Set.of("A-1") : Set.of();
            assertEquals(expected, found(harness, filter("ÉTÉ", null, List.of(), null, List.of(), List.of())));
        }
    }

    @SqlContractTest
    void searchOrdersEqualSortKeysByIdAndPages(DialectTarget target) {
        try (TicketHarness harness = TicketHarness.start(target, dataDirectory)) {
            harness.insert(ticket("A-2"), ticket("A-3"), ticket("A-1"),
                ticket("A-0").created(NOW.minusSeconds(60)).updatedAt(LATER));

            assertEquals(List.of("A-3", "A-2", "A-1", "A-0"), page(harness, TicketSortOption.NEWEST, 1, 10));
            assertEquals(List.of("A-0", "A-1", "A-2", "A-3"), page(harness, TicketSortOption.OLDEST, 1, 10));
            assertEquals(List.of("A-0", "A-3", "A-2", "A-1"), page(harness, TicketSortOption.RECENTLY_UPDATED, 1, 10));
            assertEquals(List.of("A-1", "A-2", "A-3", "A-0"), page(harness, TicketSortOption.LEAST_RECENTLY_UPDATED, 1, 10));
            assertEquals(List.of("A-1", "A-0"), page(harness, TicketSortOption.NEWEST, 2, 2));
            assertEquals(4, harness.tickets().search(filter(null, null, List.of(), null, List.of(), List.of()),
                TicketSortOption.NEWEST, 2, 2).total());
        }
    }

    @SqlContractTest
    void onlyFreeTextQueriesUseTheReportLane(DialectTarget target) {
        try (TicketHarness harness = TicketHarness.start(target, dataDirectory)) {
            TicketSearchFilter text = filter("x", null, List.of(), null, List.of(), List.of());
            TicketSearchFilter author = filter(null, null, List.of(), "x", List.of(), List.of());
            TicketSearchFilter plain = filter(null, "open", List.of(), null, List.of(), List.of());

            assertThrows(IllegalStateException.class, () -> harness.database().unitOfWork()
                .read(() -> harness.tickets().search(text, TicketSortOption.NEWEST, 1, 10)));
            assertThrows(IllegalStateException.class, () -> harness.database().unitOfWork()
                .read(() -> harness.tickets().count(author)));
            assertEquals(0, harness.database().unitOfWork()
                .read(() -> harness.tickets().search(plain, TicketSortOption.NEWEST, 1, 10)).total());
        }
    }

    @SqlContractTest
    void summariesCarryTheLastReplyAndTheCount(DialectTarget target) {
        try (TicketHarness harness = TicketHarness.start(target, dataDirectory)) {
            harness.insert(ticket("A-1").replies(List.of(reply("first").build())).tags(List.of("a")), ticket("A-2"));
            harness.tickets().appendReply("A-1", reply("last").build(), LATER);

            List<TicketSummary> summaries = harness.tickets().findSummaries(List.of("A-2", "A-1", "A-404"));

            assertEquals(List.of("A-1", "A-2"), summaries.stream().map(TicketSummary::getId).toList());
            assertEquals("last", summaries.getFirst().getLastReply().getId());
            assertEquals(2, summaries.getFirst().getReplyCount());
            assertEquals(List.of("a"), summaries.getFirst().getTags());
            assertNull(summaries.getLast().getLastReply());
            assertEquals(Set.of("A-1"), harness.tickets().firstReplies(List.of("A-1", "A-2")).keySet());
            assertEquals("first", harness.tickets().firstReplies(List.of("A-1")).get("A-1").getId());
        }
    }

    @SqlContractTest
    void playerListsMatchBothRolesNewestFirst(DialectTarget target) {
        try (TicketHarness harness = TicketHarness.start(target, dataDirectory)) {
            harness.insert(
                ticket("A-1").creatorUuid(CREATOR).tags(List.of("x")),
                ticket("A-2").reportedPlayerUuid(CREATOR).created(LATER).replayUrl("replay-2"),
                ticket("A-3").creatorUuid(CREATOR).reportedPlayerUuid(CREATOR).created(NOW.minusSeconds(1)).replayUrl(""),
                ticket("A-4").creatorUuid(CREATOR).status(TicketStatus.UNFINISHED),
                ticket("A-5").creatorUuid(REPORTED));

            assertEquals(List.of("A-2", "A-1", "A-3"), ids(harness.tickets().findByPlayer(CREATOR.toUpperCase(Locale.ROOT))));
            assertEquals(List.of("A-4", "A-1"), ids(harness.tickets().findRecentByCreator(CREATOR, 2)));
            assertEquals(List.of("A-2", "A-3"), ids(harness.tickets().findReportedPlayerTickets(CREATOR, 10)));
            assertEquals(List.of("A-2"), ids(harness.tickets().findPlayerTicketsWithReplayUrl(CREATOR, 10)));
            harness.insert(ticket("A-6").reportedPlayerUuid(CREATOR).created(LATER.plusSeconds(1)).replayUrl(""));
            assertEquals(List.of("A-2"), ids(harness.tickets().findPlayerTicketsWithReplayUrl(CREATOR, 1)));
            assertEquals(List.of("A-1"), ids(harness.tickets().findByTag("x")));
        }
    }

    @SqlContractTest
    void minecraftListsFilterByTypeStatusAndPlayer(DialectTarget target) {
        try (TicketHarness harness = TicketHarness.start(target, dataDirectory)) {
            harness.insert(
                ticket("SUPPORT-1"),
                ticket("BUG-1").type(TicketCategory.BUG).status(TicketStatus.CLOSED).created(LATER),
                ticket("STAFF-1").type(TicketCategory.APPLICATION),
                ticket("PLAYER-1").type(TicketCategory.PLAYER).reportedPlayerUuid(REPORTED),
                ticket("CHAT-1").type(TicketCategory.CHAT).created(LATER));

            assertEquals(List.of("BUG-1", "SUPPORT-1"), listedIds(harness.tickets().findMinecraftTickets(null, null, 50)));
            assertEquals(List.of("SUPPORT-1"), listedIds(harness.tickets().findMinecraftTickets("open", null, 50)));
            assertEquals(List.of("STAFF-1"), listedIds(harness.tickets().findMinecraftTickets("all", "staff", 50)));
            assertEquals(List.of("CHAT-1", "PLAYER-1"), listedIds(harness.tickets().findReports("open", null, 50, true)));
            assertEquals(List.of("PLAYER-1", "CHAT-1"), listedIds(harness.tickets().findReports("all", null, 50, false)));
            assertEquals(List.of("PLAYER-1"), listedIds(harness.tickets().findReports(null, REPORTED, 50, true)));
            assertEquals(List.of("CHAT-1"), listedIds(harness.tickets().findReports(null, null, 1, true)));
        }
    }

    @SqlContractTest
    void syncAndUpdateListsSkipUnfinishedTickets(DialectTarget target) {
        try (TicketHarness harness = TicketHarness.start(target, dataDirectory)) {
            harness.insert(
                ticket("A-1").assignedTo(List.of("amy")).replies(List.of(reply("r1").build())),
                ticket("A-2").assignedTo(List.of("amy")),
                ticket("A-3").assignedTo(List.of("amy")).status(TicketStatus.UNFINISHED).replies(List.of(reply("r3").build())),
                ticket("A-4").created(LATER).updatedAt(LATER).replies(List.of(reply("r4").build())),
                ticket("A-5").created(NOW.minusSeconds(1)));

            assertEquals(List.of("A-1", "A-2", "A-4"), ids(harness.tickets().findCreatedAfterExcludingUnfinished(NOW, 20)));
            assertEquals(List.of("A-4", "A-1"),
                summaryIds(harness.tickets().findRecentActiveWithRepliesByIds(List.of("A-1", "A-2", "A-3", "A-4"), 10)));
            assertEquals(List.of("A-1"), summaryIds(harness.tickets().findRecentAssignedWithReplies("amy", 10)));
            assertEquals(List.of("A-1", "A-2"), listedIds(harness.tickets().findListedByIds(List.of("A-2", "A-1", "A-404"))));
        }
    }

    @SqlContractTest
    void listedTicketsCarryAThreadDigestInsteadOfTheThread(DialectTarget target) {
        try (TicketHarness harness = TicketHarness.start(target, dataDirectory)) {
            harness.insert(
                ticket("PLAYER-1").type(TicketCategory.PLAYER).replies(List.of(
                    reply("opening").content("he was flying").build(),
                    reply("answer").staff(true).content("looking").build())),
                ticket("PLAYER-2").type(TicketCategory.PLAYER).created(LATER).replies(List.of(
                    reply("only").content("spam").build())),
                ticket("PLAYER-3").type(TicketCategory.PLAYER).created(LATER.plusSeconds(1)));

            Map<String, ListedTicket> listed = harness.tickets().findReports(null, null, 50, true).stream()
                .collect(Collectors.toMap(ticket -> ticket.ticket().getId(), Function.identity()));

            assertEquals(new ThreadDigest(2, true, listed.get("PLAYER-1").thread().firstVisibleReply()), listed.get("PLAYER-1").thread());
            assertEquals("he was flying", listed.get("PLAYER-1").thread().firstVisibleReply().getContent());
            assertFalse(listed.get("PLAYER-2").thread().staffResponded());
            assertEquals(1, listed.get("PLAYER-2").thread().visibleReplies());
            assertEquals(ThreadDigest.EMPTY, listed.get("PLAYER-3").thread());
            assertTrue(listed.values().stream().allMatch(ticket -> ticket.ticket().getReplies().isEmpty()));
            assertTrue(listed.values().stream().allMatch(ticket -> ticket.ticket().getNotes().isEmpty()));
        }
    }

    @SqlContractTest
    void replyStampsListOnlyRepliesAfterTheFloorAndRepliesLoadBySequence(DialectTarget target) {
        try (TicketHarness harness = TicketHarness.start(target, dataDirectory)) {
            harness.insert(
                ticket("A-1").replies(List.of(
                    reply("old").created(NOW).build(),
                    reply("new").name("Mod").staff(true).created(LATER).build())),
                ticket("A-2").replies(List.of(reply("other").created(LATER).build())));

            List<ReplyStamp> stamps = harness.tickets().findReplyStamps(List.of("A-1", "A-2", "A-404", "x".repeat(600)), NOW);

            assertEquals(List.of(new ReplyStamp("A-1", 2, LATER, true, "Mod"), new ReplyStamp("A-2", 1, LATER, false, "Player")),
                stamps);
            Map<String, TicketReply> replies = harness.tickets().findReplies(Map.of("A-1", 2, "A-2", 1, "A-404", 1));
            assertEquals(Set.of("A-1", "A-2"), replies.keySet());
            assertEquals("new", replies.get("A-1").getId());
            assertEquals("content of other", replies.get("A-2").getContent());
            assertEquals(Map.of(), harness.tickets().findReplies(Map.of()));
        }
    }

    @SqlContractTest
    void theStoredCharactersOfAReplyAreWhatTheThreadSizeCounts(DialectTarget target) {
        try (TicketHarness harness = TicketHarness.start(target, dataDirectory)) {
            harness.insert(ticket("A-1"));
            TicketReply reply = reply("r1").content("h\uD83D\uDE00llo").attachments(List.of(Map.of("url", "/a.png", "name", "\u00e9"))).build();

            harness.database().unitOfWork().write(() -> harness.tickets().appendReply("A-1", reply, NOW));

            assertEquals(harness.tickets().findThreadSize("A-1").orElseThrow().playerCharacters(),
                harness.tickets().storedCharacters(reply));
            assertEquals(1, harness.tickets().findThreadSize("A-1").orElseThrow().replies());
        }
    }

    @SqlContractTest
    void replayReferencesCanBeHeldListedAndCleared(DialectTarget target) {
        try (TicketHarness harness = TicketHarness.start(target, dataDirectory)) {
            harness.insert(
                ticket("A-1").replayId("open-replay").replayUrl("open-replay"),
                ticket("A-2").replayId("closed-replay").replayUrl("closed-replay").status(TicketStatus.CLOSED),
                ticket("A-3").replayId("draft-replay").replayUrl("draft-replay").status(TicketStatus.UNFINISHED));
            List<String> replays = List.of("open-replay", "closed-replay", "draft-replay");

            assertEquals(Set.of("open-replay", "draft-replay"),
                harness.tickets().findReplayIdsReferencedByUnresolvedTicket(replays));
            assertEquals(Map.of("A-1", "open-replay", "A-2", "closed-replay", "A-3", "draft-replay"),
                harness.tickets().findReplayIdsByTicketId(replays));
            assertEquals(1, harness.tickets().clearReplayReferences(List.of("closed-replay", " ")));
            assertNull(harness.stored("A-2").getReplayUrl());
            assertNull(harness.stored("A-2").getReplayId());
            assertEquals("open-replay", harness.stored("A-1").getReplayId());
        }
    }

    @SqlContractTest
    void deletingATicketRemovesItsChildren(DialectTarget target) {
        try (TicketHarness harness = TicketHarness.start(target, dataDirectory)) {
            harness.insert(ticket("A-1").tags(List.of("a")).assignedTo(List.of("amy")).replies(List.of(reply("r").build()))
                .notes(List.of(TicketNote.builder().text("n").build())));

            harness.database().unitOfWork().write(() -> {
                harness.database().dsl().deleteFrom(TICKETS).where(TICKETS.ID.eq("A-1")).execute();
            });

            assertEquals(0, harness.database().dsl().fetchCount(TICKET_REPLIES));
            assertEquals(0, harness.database().dsl().fetchCount(TICKET_NOTES));
            assertEquals(0, harness.database().dsl().fetchCount(TICKET_TAGS));
            assertEquals(0, harness.database().dsl().fetchCount(TICKET_ASSIGNEES));
        }
    }

    private static TicketSearchFilter filter(String search, String status, List<String> types, String author,
                                             List<String> labels, List<String> assignees) {
        return new TicketSearchFilter(search, status, types, author, labels, assignees);
    }

    private static Set<String> found(TicketHarness harness, TicketSearchFilter filter) {
        TicketSearchPage page = harness.tickets().search(filter, TicketSortOption.NEWEST, 1, 50);
        Set<String> ids = Set.copyOf(page.tickets().stream().map(TicketSummary::getId).toList());
        assertEquals(ids.size(), page.total());
        return ids;
    }

    private static List<String> page(TicketHarness harness, TicketSortOption sort, int page, int limit) {
        return harness.tickets().search(filter(null, null, List.of(), null, List.of(), List.of()), sort, page, limit)
            .tickets().stream().map(TicketSummary::getId).toList();
    }

    private static List<String> ids(List<Ticket> tickets) {
        return tickets.stream().map(Ticket::getId).toList();
    }

    private static List<String> listedIds(List<ListedTicket> tickets) {
        return ids(tickets.stream().map(ListedTicket::ticket).toList());
    }

    private static List<String> summaryIds(List<TicketSummary> tickets) {
        return tickets.stream().map(TicketSummary::getId).toList();
    }

    private static int replyCount(TicketHarness harness, String id) {
        return harness.database().dsl().fetchSingle(TICKETS, TICKETS.ID.eq(id)).getReplyCount();
    }
}

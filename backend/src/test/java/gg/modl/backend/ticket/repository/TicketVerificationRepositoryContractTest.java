package gg.modl.backend.ticket.repository;

import static gg.modl.backend.database.jooq.Tables.TICKETS;
import static gg.modl.backend.database.jooq.Tables.TICKET_VERIFICATIONS;
import static gg.modl.backend.ticket.TicketHarness.NOW;
import static gg.modl.backend.ticket.TicketHarness.ticket;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import gg.modl.backend.infrastructure.onetimecode.OneTimeCodeConditions;
import gg.modl.backend.support.sql.DialectTarget;
import gg.modl.backend.support.sql.SqlContractTest;
import gg.modl.backend.ticket.TicketHarness;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import org.junit.jupiter.api.io.TempDir;

class TicketVerificationRepositoryContractTest {
    private static final String TICKET = "SUPPORT-100001";
    private static final String OTHER_TICKET = "SUPPORT-100002";
    private static final String CODE_HASH = "code-hash";
    private static final String TOKEN_HASH = "a".repeat(64);
    private static final Instant EXPIRES = NOW.plusSeconds(300);
    private static final int THREADS = 8;
    private static final long CONSUME_SECONDS = 60;

    @TempDir
    Path dataDirectory;

    @SqlContractTest
    void aNewCodeReplacesTheOldCodeAndKeepsTokens(DialectTarget target) {
        try (TicketHarness harness = seeded(target)) {
            harness.verifications().insertToken(token("token"));
            harness.verifications().replaceCode(code("first", "first-hash"));
            harness.verifications().replaceCode(code("second", CODE_HASH));

            assertEquals(List.of("second", "token"), storedIds(harness));
            assertTrue(harness.verifications().consumeMatchingCode(TICKET, "first-hash", NOW).isEmpty());
        }
    }

    @SqlContractTest
    void consumeReturnsTheRowOnceAndDeletesIt(DialectTarget target) {
        try (TicketHarness harness = seeded(target)) {
            TicketVerificationRecord issued = code("code", CODE_HASH);
            harness.verifications().replaceCode(issued);

            assertEquals(issued, harness.verifications().consumeMatchingCode(TICKET, CODE_HASH, NOW).orElseThrow());
            assertTrue(harness.verifications().consumeMatchingCode(TICKET, CODE_HASH, NOW).isEmpty());
            assertEquals(List.of(), storedIds(harness));
        }
    }

    @SqlContractTest
    void concurrentConsumersProduceOneWinner(DialectTarget target) throws Exception {
        try (TicketHarness harness = seeded(target)) {
            harness.verifications().replaceCode(code("code", CODE_HASH));
            List<Callable<Boolean>> consumers = IntStream.range(0, THREADS)
                .<Callable<Boolean>>mapToObj(index ->
                    () -> harness.verifications().consumeMatchingCode(TICKET, CODE_HASH, NOW).isPresent())
                .toList();

            ExecutorService threads = Executors.newFixedThreadPool(THREADS);
            int winners = 0;
            try {
                for (Future<Boolean> consumed : threads.invokeAll(consumers, CONSUME_SECONDS, TimeUnit.SECONDS)) {
                    if (consumed.get()) {
                        winners++;
                    }
                }
            } finally {
                threads.shutdownNow();
            }

            assertEquals(1, winners);
        }
    }

    @SqlContractTest
    void aCodeIsBoundToItsTicketHashAndExpiry(DialectTarget target) {
        try (TicketHarness harness = seeded(target)) {
            harness.verifications().replaceCode(code("code", CODE_HASH));

            assertTrue(harness.verifications().consumeMatchingCode(OTHER_TICKET, CODE_HASH, NOW).isEmpty());
            assertTrue(harness.verifications().consumeMatchingCode(TICKET, CODE_HASH.toUpperCase(Locale.ROOT), NOW).isEmpty());
            assertTrue(harness.verifications().consumeMatchingCode(TICKET, CODE_HASH, EXPIRES).isEmpty());
            assertFalse(harness.verifications().incrementFailedAttempts(TICKET, EXPIRES));
            assertEquals(List.of("code"), storedIds(harness));
        }
    }

    @SqlContractTest
    void tooManyFailuresLockTheCode(DialectTarget target) {
        try (TicketHarness harness = seeded(target)) {
            harness.verifications().replaceCode(code("code", CODE_HASH));
            for (int attempt = 0; attempt < OneTimeCodeConditions.MAX_FAILED_ATTEMPTS; attempt++) {
                assertTrue(harness.verifications().incrementFailedAttempts(TICKET, NOW));
            }

            assertTrue(harness.verifications().consumeMatchingCode(TICKET, CODE_HASH, NOW).isEmpty());
        }
    }

    @SqlContractTest
    void tokensAreBoundToTheirTicketAndExpiry(DialectTarget target) {
        try (TicketHarness harness = seeded(target)) {
            harness.verifications().insertToken(token("token"));

            assertTrue(harness.verifications().existsActiveToken(TICKET, TOKEN_HASH, EXPIRES));
            assertFalse(harness.verifications().existsActiveToken(TICKET, TOKEN_HASH, EXPIRES.plusMillis(1)));
            assertFalse(harness.verifications().existsActiveToken(OTHER_TICKET, TOKEN_HASH, NOW));
            assertFalse(harness.verifications().existsActiveToken(TICKET, TOKEN_HASH.toUpperCase(Locale.ROOT), NOW));
            assertFalse(harness.verifications().existsActiveToken(TICKET, "a".repeat(65), NOW));
            assertFalse(harness.verifications().incrementFailedAttempts(TICKET, NOW));
        }
    }

    @SqlContractTest
    void deletingTheTicketRemovesItsVerifications(DialectTarget target) {
        try (TicketHarness harness = seeded(target)) {
            harness.verifications().insertToken(token("token"));

            harness.database().unitOfWork().write(() -> {
                harness.database().dsl().deleteFrom(TICKETS).where(TICKETS.ID.eq(TICKET)).execute();
            });

            assertEquals(List.of(), storedIds(harness));
        }
    }

    @SqlContractTest
    void failuresAreCountedInOneWindowThatANewCodeDoesNotReset(DialectTarget target) {
        try (TicketHarness harness = seeded(target)) {
            Instant windowEnd = NOW.plusSeconds(3600);
            assertTrue(harness.verifications().findFailures(TICKET, NOW).isEmpty());

            harness.database().unitOfWork().write(() -> {
                harness.verifications().countFailure(TICKET, NOW, windowEnd);
                harness.verifications().countFailure(TICKET, NOW.plusSeconds(1), NOW.plusSeconds(9999));
            });
            harness.verifications().replaceCode(code("fresh", CODE_HASH));

            assertEquals(new VerificationFailures(2, windowEnd), harness.verifications().findFailures(TICKET, NOW).orElseThrow());
            assertTrue(harness.verifications().findFailures(OTHER_TICKET, NOW).isEmpty());
            assertTrue(harness.verifications().findFailures(TICKET, windowEnd).isEmpty());

            harness.database().unitOfWork().write(() -> harness.verifications().countFailure(TICKET, windowEnd, windowEnd.plusSeconds(60)));
            assertEquals(new VerificationFailures(1, windowEnd.plusSeconds(60)),
                harness.verifications().findFailures(TICKET, windowEnd).orElseThrow());
        }
    }

    @SqlContractTest
    void theCodeExpiryIsTheLatestCodeAndIgnoresTokensAndFailures(DialectTarget target) {
        try (TicketHarness harness = seeded(target)) {
            assertTrue(harness.verifications().findCodeExpiry(TICKET).isEmpty());
            harness.verifications().insertToken(token("token"));
            harness.database().unitOfWork().write(() -> harness.verifications().countFailure(TICKET, NOW, NOW.plusSeconds(9999)));
            assertTrue(harness.verifications().findCodeExpiry(TICKET).isEmpty());

            harness.verifications().replaceCode(code("code", CODE_HASH));

            assertEquals(EXPIRES, harness.verifications().findCodeExpiry(TICKET).orElseThrow());
            assertTrue(harness.verifications().findCodeExpiry(OTHER_TICKET).isEmpty());
        }
    }

    private TicketHarness seeded(DialectTarget target) {
        TicketHarness harness = TicketHarness.start(target, dataDirectory);
        harness.insert(ticket(TICKET), ticket(OTHER_TICKET));
        return harness;
    }

    private static TicketVerificationRecord code(String id, String codeHash) {
        return new TicketVerificationRecord(id, TICKET, null, codeHash, "player@example.com", 0, EXPIRES);
    }

    private static TicketVerificationRecord token(String id) {
        return new TicketVerificationRecord(id, TICKET, TOKEN_HASH, null, "player@example.com", 0, EXPIRES);
    }

    private static List<String> storedIds(TicketHarness harness) {
        return harness.database().dsl().select(TICKET_VERIFICATIONS.ID).from(TICKET_VERIFICATIONS)
            .orderBy(TICKET_VERIFICATIONS.ID).fetch(TICKET_VERIFICATIONS.ID);
    }
}

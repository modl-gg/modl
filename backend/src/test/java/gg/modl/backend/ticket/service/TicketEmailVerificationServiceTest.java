package gg.modl.backend.ticket.service;

import gg.modl.backend.infrastructure.onetimecode.OneTimeCodeConditions;
import gg.modl.backend.infrastructure.exception.TooManyRequestsException;
import gg.modl.backend.infrastructure.exception.BaseApplicationException;
import gg.modl.backend.email.EmailHTMLTemplate;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.any;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static gg.modl.backend.database.jooq.Tables.TICKET_VERIFICATIONS;
import static gg.modl.backend.ticket.TicketHarness.NOW;
import static gg.modl.backend.ticket.TicketHarness.ticket;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import gg.modl.backend.database.jooq.tables.records.TicketVerificationsRecord;
import gg.modl.backend.email.EmailService;
import gg.modl.backend.infrastructure.exception.ValidationException;
import gg.modl.backend.infrastructure.ratelimit.BucketPool;
import gg.modl.backend.infrastructure.onetimecode.OneTimeCodeCodec;
import gg.modl.backend.infrastructure.util.DigestUtils;
import gg.modl.backend.server.data.Server;
import gg.modl.backend.support.TestServers;
import gg.modl.backend.support.sql.DialectTarget;
import gg.modl.backend.support.sql.RetryInjection;
import gg.modl.backend.support.sql.SqlContractTest;
import gg.modl.backend.ticket.TicketHarness;
import gg.modl.backend.ticket.config.TicketEmailVerificationConfiguration;
import gg.modl.backend.ticket.repository.TicketVerificationRecord;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.io.TempDir;

class TicketEmailVerificationServiceTest {
    private static final String TICKET = "SUPPORT-100001";
    private static final String CODE = "123456";
    private static final String NETWORK = "203.0.113.7";

    private final Server server = TestServers.server("server", "admin@example.com");
    private final OneTimeCodeCodec codec = new OneTimeCodeCodec(() -> "test-secret");
    private final EmailService emailService = mock(EmailService.class);

    @TempDir
    Path dataDirectory;

    @SqlContractTest
    void aFailedVerificationIsCountedOnce(DialectTarget target) {
        try (TicketHarness harness = withCode(target)) {
            assertNull(service(harness).verifyCode(server, TICKET, "000000", NETWORK));

            assertEquals(1, harness.database().dsl().fetchSingle(TICKET_VERIFICATIONS, TICKET_VERIFICATIONS.CODE_HASH.isNotNull())
                .getFailedAttempts());
        }
    }

    @SqlContractTest
    void theIssuedTokenIsStoredOnlyAsAHash(DialectTarget target) {
        try (TicketHarness harness = withCode(target)) {
            TicketEmailVerificationService service = service(harness);

            String token = service.verifyCode(server, TICKET, CODE, NETWORK);

            TicketVerificationsRecord stored = harness.database().dsl().fetchSingle(TICKET_VERIFICATIONS);
            assertEquals(DigestUtils.sha256Hex(token), stored.getTokenHash());
            assertNull(stored.getCodeHash());
            assertTrue(service.validateToken(server, TICKET, token));
            assertFalse(service.validateToken(server, TICKET, stored.getTokenHash()));

            harness.database().clock().advance(Duration.ofSeconds(301));
            assertTrue(service.validateToken(server, TICKET, token));
            harness.database().clock().advance(Duration.ofHours(24));
            assertFalse(service.validateToken(server, TICKET, token));
        }
    }

    @SqlContractTest
    void theCodeStaysShortLivedAndSingleUse(DialectTarget target) {
        try (TicketHarness harness = withCode(target)) {
            TicketEmailVerificationService service = service(harness);
            service.verifyCode(server, TICKET, CODE, NETWORK);
            assertNull(service.verifyCode(server, TICKET, CODE, NETWORK));
        }
        try (TicketHarness harness = withCode(target, dataDirectory.resolve("expired"))) {
            harness.database().clock().advance(Duration.ofSeconds(301));
            assertNull(service(harness).verifyCode(server, TICKET, CODE, NETWORK));
        }
    }

    @SqlContractTest
    void sendingStoresAHashedCodeForTheNormalisedEmail(DialectTarget target) {
        try (TicketHarness harness = TicketHarness.start(target, dataDirectory, new RetryInjection())) {
            harness.insert(ticket(TICKET).data(Map.of("creatorEmail", " Player@Example.com ")));

            service(harness).sendVerificationCode(server, harness.stored(TICKET));

            TicketVerificationsRecord stored = harness.database().dsl().fetchSingle(TICKET_VERIFICATIONS);
            assertEquals("player@example.com", stored.getEmail());
            assertEquals(codec.hash("000000").length(), stored.getCodeHash().length());
            assertEquals(NOW.plusSeconds(300), stored.getExpiresAt());
        }
    }

    @SqlContractTest
    void anInvalidStoredEmailNeverReachesTheTable(DialectTarget target) {
        try (TicketHarness harness = TicketHarness.start(target, dataDirectory, new RetryInjection())) {
            harness.insert(ticket(TICKET).data(Map.of("creatorEmail", "x".repeat(300) + "@example.com")));

            assertThrows(ValidationException.class,
                () -> service(harness).sendVerificationCode(server, harness.stored(TICKET)));

            assertEquals(0, harness.database().dsl().fetchCount(TICKET_VERIFICATIONS));
            verifyNoInteractions(emailService);
        }
    }

    @SqlContractTest
    void aNewCodeCanBeAskedForOnlyAfterTheCooldown(DialectTarget target) {
        try (TicketHarness harness = withContact(target)) {
            TicketEmailVerificationService service = service(harness);
            service.sendVerificationCode(server, harness.stored(TICKET));

            assertThatThrownBy(() -> service.sendVerificationCode(server, harness.stored(TICKET)))
                .isInstanceOf(TooManyRequestsException.class)
                .extracting(failure -> ((BaseApplicationException) failure).getMachineCode())
                .isEqualTo(TicketEmailVerificationService.RESEND_COOLDOWN_CODE);

            harness.database().clock().advance(Duration.ofSeconds(61));
            service.sendVerificationCode(server, harness.stored(TICKET));
            verify(emailService, times(2)).send(anyString(), any(EmailHTMLTemplate.HTMLEmail.class));
        }
    }

    @SqlContractTest
    void wrongGuessesCountAcrossNewCodesUntilTheRecordIsLocked(DialectTarget target) {
        try (TicketHarness harness = withContact(target)) {
            TicketEmailVerificationService service = service(harness);
            int perCode = OneTimeCodeConditions.MAX_FAILED_ATTEMPTS;
            for (int round = 0; round < 20 / perCode; round++) {
                service.sendVerificationCode(server, harness.stored(TICKET));
                for (int guess = 0; guess < perCode + 2; guess++) {
                    assertNull(service.verifyCode(server, TICKET, "000000", "198.51.100." + round + guess));
                }
                harness.database().clock().advance(Duration.ofSeconds(61));
            }

            assertThatThrownBy(() -> service.sendVerificationCode(server, harness.stored(TICKET)))
                .isInstanceOf(TooManyRequestsException.class)
                .extracting(failure -> ((BaseApplicationException) failure).getMachineCode())
                .isEqualTo(TicketEmailVerificationService.FAILURE_BUDGET_CODE);
            harness.verifications().replaceCode(new TicketVerificationRecord(
                "planted", TICKET, null, codec.hash(CODE), "player@example.com", 0, harness.database().clock().instant().plusSeconds(300)));
            assertNull(service.verifyCode(server, TICKET, CODE, NETWORK));
        }
    }

    @SqlContractTest
    void oneNetworkSpendsOnlyItsOwnShareSoTheOwnerCanStillVerify(DialectTarget target) {
        try (TicketHarness harness = withCode(target)) {
            TicketEmailVerificationService service = service(harness);
            for (int guess = 0; guess < VerificationClientBudgets.FAILURES_PER_NETWORK.capacity(); guess++) {
                assertNull(service.verifyCode(server, TICKET, "000000", "192.0.2.66"));
            }

            assertThatThrownBy(() -> service.verifyCode(server, TICKET, "000000", "192.0.2.66"))
                .isInstanceOf(TooManyRequestsException.class)
                .extracting(failure -> ((BaseApplicationException) failure).getMachineCode())
                .isEqualTo(TicketEmailVerificationService.FAILURE_BUDGET_CODE);
            harness.verifications().replaceCode(new TicketVerificationRecord(
                "fresh", TICKET, null, codec.hash(CODE), "player@example.com", 0, NOW.plusSeconds(300)));
            assertNotNull(service.verifyCode(server, TICKET, CODE, NETWORK));
        }
    }

    @SqlContractTest
    void aRecordGetsOnlyABoundedNumberOfCodeMailsPerDay(DialectTarget target) {
        try (TicketHarness harness = withContact(target)) {
            TicketEmailVerificationService service = service(harness);
            for (int send = 0; send < VerificationClientBudgets.SENDS_PER_RECORD.capacity(); send++) {
                service.sendVerificationCode(server, harness.stored(TICKET));
                harness.database().clock().advance(Duration.ofSeconds(61));
            }

            assertThatThrownBy(() -> service.sendVerificationCode(server, harness.stored(TICKET)))
                .isInstanceOf(TooManyRequestsException.class);
            verify(emailService, times(VerificationClientBudgets.SENDS_PER_RECORD.capacity()))
                .send(anyString(), any(EmailHTMLTemplate.HTMLEmail.class));
        }
    }

    private TicketHarness withContact(DialectTarget target) {
        TicketHarness harness = TicketHarness.start(target, dataDirectory, new RetryInjection());
        harness.insert(ticket(TICKET).data(Map.of("creatorEmail", "player@example.com")));
        return harness;
    }

    private TicketHarness withCode(DialectTarget target) {
        return withCode(target, dataDirectory);
    }

    private TicketHarness withCode(DialectTarget target, Path directory) {
        TicketHarness harness = TicketHarness.start(target, directory, new RetryInjection());
        harness.insert(ticket(TICKET));
        harness.verifications().replaceCode(new TicketVerificationRecord(
            "code", TICKET, null, codec.hash(CODE), "player@example.com", 0, NOW.plusSeconds(300)));
        return harness;
    }

    private TicketEmailVerificationService service(TicketHarness harness) {
        return service(harness, new VerificationClientBudgets(new BucketPool()));
    }

    private TicketEmailVerificationService service(TicketHarness harness, VerificationClientBudgets budgets) {
        return new TicketEmailVerificationService(harness.verifications(), emailService,
            new TicketEmailVerificationConfiguration(), codec, budgets, harness.database().unitOfWork(), harness.database().clock());
    }
}

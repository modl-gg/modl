package gg.modl.backend.auth.repository;

import static gg.modl.backend.database.jooq.Tables.AUTH_CODES;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import gg.modl.backend.auth.data.AuthCode;
import gg.modl.backend.database.ValueTooLongException;
import gg.modl.backend.database.jooq.tables.records.AuthCodesRecord;
import gg.modl.backend.infrastructure.onetimecode.OneTimeCodeConditions;
import gg.modl.backend.support.sql.DialectTarget;
import gg.modl.backend.support.sql.RetryInjection;
import gg.modl.backend.support.sql.SqlContractTest;
import gg.modl.backend.support.sql.SqlTestDatabase;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.io.TempDir;

class AuthCodeRepositoryContractTest {
    private static final String EMAIL = "mod@example.com";
    private static final Instant NOW = SqlTestDatabase.START;
    private static final Duration CODE_LIFETIME = Duration.ofMinutes(5);
    private static final Duration COOLDOWN = Duration.ofSeconds(60);
    private static final Duration WINDOW = Duration.ofHours(24);
    private static final int FAILURE_LIMIT = 20;

    @TempDir
    Path dataDirectory;

    @SqlContractTest
    void theFirstIssueStoresAFreshCode(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory)) {
            AuthCodeRepository codes = AuthRepositories.on(database).authCodes();

            assertThat(issue(codes, "first", NOW)).isTrue();

            AuthCode found = codes.find(" Mod@Example.com ").orElseThrow();
            assertThat(found.getEmail()).isEqualTo(EMAIL);
            assertThat(found.getCodeHash()).isEqualTo("first");
            assertThat(found.getIssuedAt()).isEqualTo(NOW);
            assertThat(found.getCodeExpiresAt()).isEqualTo(NOW.plus(CODE_LIFETIME));
            assertThat(found.getExpiresAt()).isEqualTo(NOW.plus(CODE_LIFETIME));
            assertThat(found.getFailedAttempts()).isZero();
            assertThat(found.getRecentFailures()).isZero();
            assertThat(found.getFailureWindowStartedAt()).isNull();
        }
    }

    @SqlContractTest
    void anIssueInsideTheCooldownChangesNothing(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory)) {
            AuthCodeRepository codes = AuthRepositories.on(database).authCodes();
            issue(codes, "first", NOW);
            fail(codes, NOW.plusSeconds(10));
            AuthCodesRecord before = stored(database);

            assertThat(issue(codes, "second", NOW.plusSeconds(30))).isFalse();
            assertThat(issue(codes, "third", NOW.plus(COOLDOWN).minusMillis(1))).isFalse();

            AuthCodesRecord after = stored(database);
            assertThat(after.intoList()).isEqualTo(before.intoList());
            assertThat(after.getFailedAttempts()).isEqualTo(1);
            assertThat(after.getRecentFailures()).isEqualTo(1);
        }
    }

    @SqlContractTest
    void anIssueAfterTheCooldownResetsOnlyTheAttemptsOfTheCode(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory)) {
            AuthCodeRepository codes = AuthRepositories.on(database).authCodes();
            issue(codes, "first", NOW);
            Instant failedAt = NOW.plusSeconds(10);
            fail(codes, failedAt);

            assertThat(issue(codes, "second", NOW.plus(COOLDOWN))).isTrue();

            AuthCodesRecord after = stored(database);
            assertThat(after.getCodeHash()).isEqualTo("second");
            assertThat(after.getIssuedAt()).isEqualTo(NOW.plus(COOLDOWN));
            assertThat(after.getCodeExpiresAt()).isEqualTo(NOW.plus(COOLDOWN).plus(CODE_LIFETIME));
            assertThat(after.getFailedAttempts()).isZero();
            assertThat(after.getRecentFailures()).isEqualTo(1);
            assertThat(after.getFailureWindowStartedAt()).isEqualTo(failedAt);
            assertThat(after.getExpiresAt()).isEqualTo(failedAt.plus(WINDOW));
        }
    }

    @SqlContractTest
    void anIssueExtendsTheRetentionWhenTheNewCodeOutlivesIt(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory)) {
            AuthCodeRepository codes = AuthRepositories.on(database).authCodes();
            issue(codes, "first", NOW);
            Instant reissuedAt = NOW.plus(Duration.ofMinutes(2));

            assertThat(issue(codes, "second", reissuedAt)).isTrue();

            assertThat(stored(database).getExpiresAt()).isEqualTo(reissuedAt.plus(CODE_LIFETIME));
        }
    }

    @SqlContractTest
    void simultaneousIssuesForOneEmailSucceedExactlyOnce(DialectTarget target) throws Exception {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory)) {
            AuthCodeRepository codes = AuthRepositories.on(database).authCodes();

            List<Boolean> first = SimultaneousCalls.run(SimultaneousCalls.CALLERS, caller -> issue(codes, "first-" + caller, NOW));
            Instant later = NOW.plus(COOLDOWN);
            List<Boolean> again = SimultaneousCalls.run(SimultaneousCalls.CALLERS, caller -> issue(codes, "again-" + caller, later));

            assertThat(first).containsOnlyOnce(true);
            assertThat(again).containsOnlyOnce(true);
            assertThat(stored(database).getCodeHash()).isEqualTo("again-" + again.indexOf(true));
        }
    }

    @SqlContractTest
    void aRetriedIssueStillReportsSuccess(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory, new RetryInjection())) {
            AuthCodeRepository codes = AuthRepositories.on(database).authCodes();

            assertThat(issue(codes, "first", NOW)).isTrue();
            assertThat(issue(codes, "second", NOW.plus(COOLDOWN))).isTrue();
            assertThat(stored(database).getCodeHash()).isEqualTo("second");
        }
    }

    @SqlContractTest
    void theRightCodeIsConsumedOnceUnderConcurrency(DialectTarget target) throws Exception {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory)) {
            AuthCodeRepository codes = AuthRepositories.on(database).authCodes();
            issue(codes, "right", NOW);

            List<Boolean> consumed = SimultaneousCalls.run(SimultaneousCalls.CALLERS,
                caller -> consume(codes, "right", NOW.plusSeconds(1)).isPresent());

            assertThat(consumed).containsOnlyOnce(true);
            assertThat(database.dsl().fetchCount(AUTH_CODES)).isZero();
        }
    }

    @SqlContractTest
    void consumingReturnsTheConsumedCode(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory)) {
            AuthCodeRepository codes = AuthRepositories.on(database).authCodes();
            issue(codes, "right", NOW);
            fail(codes, NOW);

            AuthCode consumed = consume(codes, "right", NOW.plusSeconds(1)).orElseThrow();

            assertThat(consumed.getEmail()).isEqualTo(EMAIL);
            assertThat(consumed.getRecentFailures()).isEqualTo(1);
            assertThat(consume(codes, "right", NOW.plusSeconds(2))).isEmpty();
        }
    }

    @SqlContractTest
    void aWrongHashAnotherCaseAnExpiredCodeOrAnotherEmailIsNeverConsumed(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory)) {
            AuthCodeRepository codes = AuthRepositories.on(database).authCodes();
            issue(codes, "Right", NOW);

            assertThat(consume(codes, "wrong", NOW)).isEmpty();
            assertThat(consume(codes, "right", NOW)).isEmpty();
            assertThat(consume(codes, "Right", NOW.plus(CODE_LIFETIME))).isEmpty();
            assertThat(codes.consumeIfHashMatches("other@example.com", "Right", NOW, NOW.minus(WINDOW), FAILURE_LIMIT)).isEmpty();
            assertThat(database.dsl().fetchCount(AUTH_CODES)).isEqualTo(1);
        }
    }

    @SqlContractTest
    void aCodeLocksAfterFiveWrongGuesses(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory)) {
            AuthCodeRepository codes = AuthRepositories.on(database).authCodes();
            issue(codes, "right", NOW);
            for (int attempt = 0; attempt < OneTimeCodeConditions.MAX_FAILED_ATTEMPTS; attempt++) {
                fail(codes, NOW);
            }

            assertThat(consume(codes, "right", NOW.plusSeconds(1))).isEmpty();
            assertThat(stored(database).getFailedAttempts()).isEqualTo(OneTimeCodeConditions.MAX_FAILED_ATTEMPTS);
        }
    }

    @SqlContractTest
    void theFailureBudgetSurvivesReissuingAndBlocksTheRightCodeUntilTheWindowEnds(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory)) {
            AuthCodeRepository codes = AuthRepositories.on(database).authCodes();
            Instant time = NOW;
            for (int code = 0; code < FAILURE_LIMIT / OneTimeCodeConditions.MAX_FAILED_ATTEMPTS; code++) {
                assertThat(issue(codes, "code-" + code, time)).isTrue();
                for (int attempt = 0; attempt < OneTimeCodeConditions.MAX_FAILED_ATTEMPTS; attempt++) {
                    fail(codes, time);
                }
                time = time.plus(COOLDOWN);
            }
            assertThat(issue(codes, "right", time)).isTrue();

            AuthCodesRecord locked = stored(database);
            assertThat(locked.getRecentFailures()).isEqualTo(FAILURE_LIMIT);
            assertThat(locked.getFailedAttempts()).isZero();
            assertThat(locked.getExpiresAt()).isEqualTo(NOW.plus(WINDOW));
            assertThat(consume(codes, "right", time)).isEmpty();

            Instant afterWindow = NOW.plus(WINDOW);
            assertThat(issue(codes, "later", afterWindow)).isTrue();
            assertThat(consume(codes, "later", afterWindow.plusSeconds(1))).isPresent();
        }
    }

    @SqlContractTest
    void aFailureAfterTheWindowStartsANewOne(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory)) {
            AuthCodeRepository codes = AuthRepositories.on(database).authCodes();
            issue(codes, "first", NOW);
            fail(codes, NOW);
            fail(codes, NOW);
            Instant nextDay = NOW.plus(WINDOW);
            issue(codes, "second", nextDay);

            fail(codes, nextDay);

            AuthCodesRecord after = stored(database);
            assertThat(after.getRecentFailures()).isEqualTo(1);
            assertThat(after.getFailureWindowStartedAt()).isEqualTo(nextDay);
            assertThat(after.getExpiresAt()).isEqualTo(nextDay.plus(WINDOW));
        }
    }

    @SqlContractTest
    void startingAWindowNeverShortensTheRetention(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory)) {
            AuthCodeRepository codes = AuthRepositories.on(database).authCodes();
            issue(codes, "first", NOW);

            codes.recordFailedAttempt(EMAIL, NOW, NOW.minus(WINDOW), NOW.plusSeconds(1));

            assertThat(stored(database).getExpiresAt()).isEqualTo(NOW.plus(CODE_LIFETIME));
        }
    }

    @SqlContractTest
    void simultaneousFailuresAreAllCounted(DialectTarget target) throws Exception {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory)) {
            AuthCodeRepository codes = AuthRepositories.on(database).authCodes();
            issue(codes, "right", NOW);

            SimultaneousCalls.run(SimultaneousCalls.CALLERS, caller -> {
                fail(codes, NOW.plusSeconds(1));
                return caller;
            });

            assertThat(stored(database).getRecentFailures()).isEqualTo(SimultaneousCalls.CALLERS);
            assertThat(stored(database).getFailedAttempts()).isEqualTo(SimultaneousCalls.CALLERS);
        }
    }

    @SqlContractTest
    void aFailureForAnUnknownEmailOrAnExpiredCodeStoresNothing(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory)) {
            AuthCodeRepository codes = AuthRepositories.on(database).authCodes();

            fail(codes, NOW);
            assertThat(database.dsl().fetchCount(AUTH_CODES)).isZero();

            issue(codes, "right", NOW);
            AuthCodesRecord before = stored(database);
            fail(codes, NOW.plus(CODE_LIFETIME));
            assertThat(stored(database).intoList()).isEqualTo(before.intoList());
        }
    }

    @SqlContractTest
    void findIgnoresARowPastItsRetention(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory)) {
            AuthCodeRepository codes = AuthRepositories.on(database).authCodes();
            issue(codes, "right", NOW);

            database.clock().advance(CODE_LIFETIME);

            assertThat(codes.find(EMAIL)).isEmpty();
            assertThat(database.dsl().fetchCount(AUTH_CODES)).isEqualTo(1);
        }
    }

    @SqlContractTest
    void deleteRemovesOnlyThatEmail(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory)) {
            AuthCodeRepository codes = AuthRepositories.on(database).authCodes();
            issue(codes, "mine", NOW);
            codes.issue("other@example.com", "theirs", NOW, NOW.plus(CODE_LIFETIME), NOW.minus(COOLDOWN));

            codes.delete("MOD@example.com");

            assertThat(codes.find(EMAIL)).isEmpty();
            assertThat(codes.find("other@example.com")).isPresent();
        }
    }

    @SqlContractTest
    void anOverlongEmailMatchesNothingAndIsNeverStored(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory)) {
            AuthCodeRepository codes = AuthRepositories.on(database).authCodes();
            String overlong = "a".repeat(255);

            assertThat(codes.find(overlong)).isEmpty();
            assertThat(codes.consumeIfHashMatches(overlong, "hash", NOW, NOW.minus(WINDOW), FAILURE_LIMIT)).isEmpty();
            assertThatThrownBy(() -> codes.issue(overlong, "hash", NOW, NOW.plus(CODE_LIFETIME), NOW.minus(COOLDOWN)))
                .isInstanceOf(ValueTooLongException.class);
            assertThatThrownBy(() -> issue(codes, "h".repeat(256), NOW)).isInstanceOf(ValueTooLongException.class);
            assertThat(database.dsl().fetchCount(AUTH_CODES)).isZero();
        }
    }

    private static boolean issue(AuthCodeRepository codes, String hash, Instant at) {
        return codes.issue(EMAIL, hash, at, at.plus(CODE_LIFETIME), at.minus(COOLDOWN));
    }

    private static void fail(AuthCodeRepository codes, Instant at) {
        codes.recordFailedAttempt(EMAIL, at, at.minus(WINDOW), at.plus(WINDOW));
    }

    private static Optional<AuthCode> consume(AuthCodeRepository codes, String hash, Instant at) {
        return codes.consumeIfHashMatches(EMAIL, hash, at, at.minus(WINDOW), FAILURE_LIMIT);
    }

    private static AuthCodesRecord stored(SqlTestDatabase database) {
        return database.dsl().fetchSingle(AUTH_CODES, AUTH_CODES.EMAIL.eq(EMAIL));
    }
}

package gg.modl.backend.database;

import static gg.modl.backend.database.jooq.Tables.AUTH_CODES;
import static org.junit.jupiter.api.Assertions.assertEquals;

import gg.modl.backend.database.jooq.tables.records.AuthCodesRecord;
import gg.modl.backend.infrastructure.onetimecode.OneTimeCodeConditions;
import gg.modl.backend.support.sql.DialectTarget;
import gg.modl.backend.support.sql.SqlContractTest;
import gg.modl.backend.support.sql.SqlTestDatabase;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.jooq.Condition;
import org.junit.jupiter.api.io.TempDir;

class OneTimeCodeConditionsContractTest {
    private static final OneTimeCodeConditions.Columns COLUMNS =
        new OneTimeCodeConditions.Columns(AUTH_CODES.CODE_HASH, AUTH_CODES.CODE_EXPIRES_AT, AUTH_CODES.FAILED_ATTEMPTS);
    private static final Instant NOW = KernelRows.NOW;
    private static final Instant LATER = NOW.plus(Duration.ofMinutes(10));
    private static final Instant EARLIER = NOW.minus(Duration.ofMinutes(10));
    private static final String HASH = "hash-of-123456";

    @TempDir
    Path dataDirectory;

    @SqlContractTest
    void onlyTheActiveUnlockedCodeWithTheMatchingHashIsSelected(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory)) {
            KernelRows.insert(database,
                code("valid@example.com", HASH, 0, LATER),
                code("expired@example.com", HASH, 0, EARLIER),
                code("expiring@example.com", HASH, 0, NOW),
                code("locked@example.com", HASH, OneTimeCodeConditions.MAX_FAILED_ATTEMPTS, LATER),
                code("almost@example.com", HASH, OneTimeCodeConditions.MAX_FAILED_ATTEMPTS - 1, LATER),
                code("other@example.com", "another-hash", 0, LATER));

            assertEquals(List.of("valid@example.com"), matching(database, "valid@example.com", HASH));
            assertEquals(List.of(), matching(database, "expired@example.com", HASH));
            assertEquals(List.of(), matching(database, "expiring@example.com", HASH));
            assertEquals(List.of(), matching(database, "locked@example.com", HASH));
            assertEquals(List.of("almost@example.com"), matching(database, "almost@example.com", HASH));
            assertEquals(List.of(), matching(database, "other@example.com", HASH));
            assertEquals(List.of(), matching(database, "valid@example.com", "HASH-OF-123456"));
        }
    }

    @SqlContractTest
    void aCustomAttemptLimitLocksTheCodeEarlier(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory)) {
            KernelRows.insert(database, code("valid@example.com", HASH, 2, LATER));

            assertEquals(1, database.dsl().fetchCount(AUTH_CODES, OneTimeCodeConditions.activeUnlockedCode(
                AUTH_CODES.EMAIL.eq("valid@example.com"), COLUMNS, HASH, NOW, 3)));
            assertEquals(0, database.dsl().fetchCount(AUTH_CODES, OneTimeCodeConditions.activeUnlockedCode(
                AUTH_CODES.EMAIL.eq("valid@example.com"), COLUMNS, HASH, NOW, 2)));
        }
    }

    @SqlContractTest
    void guardedFailedAttemptIncrementsStopOnceTheCodeIsLocked(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory)) {
            KernelRows.insert(database, code("valid@example.com", HASH, 0, LATER));

            int counted = 0;
            for (int attempt = 0; attempt < OneTimeCodeConditions.MAX_FAILED_ATTEMPTS + 2; attempt++) {
                counted += recordFailedAttempt(database, "valid@example.com");
            }

            assertEquals(OneTimeCodeConditions.MAX_FAILED_ATTEMPTS, counted);
            assertEquals(OneTimeCodeConditions.MAX_FAILED_ATTEMPTS, failedAttempts(database, "valid@example.com"));
            assertEquals(List.of(), matching(database, "valid@example.com", HASH));
        }
    }

    @SqlContractTest
    void aFailedAttemptOnAnExpiredCodeChangesNothing(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory)) {
            KernelRows.insert(database, code("expired@example.com", HASH, 1, EARLIER));

            assertEquals(0, recordFailedAttempt(database, "expired@example.com"));
            assertEquals(1, failedAttempts(database, "expired@example.com"));
        }
    }

    private static int recordFailedAttempt(SqlTestDatabase database, String email) {
        Condition unlocked = OneTimeCodeConditions.active(AUTH_CODES.EMAIL.eq(email), COLUMNS, NOW)
            .and(AUTH_CODES.FAILED_ATTEMPTS.lt(OneTimeCodeConditions.MAX_FAILED_ATTEMPTS));
        return database.unitOfWork().write(() -> database.dsl().update(AUTH_CODES)
            .set(AUTH_CODES.FAILED_ATTEMPTS, OneTimeCodeConditions.incrementedFailedAttempts(COLUMNS))
            .where(unlocked)
            .execute());
    }

    private static List<String> matching(SqlTestDatabase database, String email, String hash) {
        return database.dsl().select(AUTH_CODES.EMAIL).from(AUTH_CODES)
            .where(OneTimeCodeConditions.activeUnlockedCode(AUTH_CODES.EMAIL.eq(email), COLUMNS, hash, NOW))
            .fetch(AUTH_CODES.EMAIL);
    }

    private static int failedAttempts(SqlTestDatabase database, String email) {
        return database.dsl().fetchSingle(AUTH_CODES, AUTH_CODES.EMAIL.eq(email)).getFailedAttempts();
    }

    private static AuthCodesRecord code(String email, String hash, int failedAttempts, Instant codeExpiresAt) {
        return new AuthCodesRecord(email, hash, failedAttempts, NOW, codeExpiresAt, 0, null, codeExpiresAt.plus(Duration.ofDays(1)));
    }
}

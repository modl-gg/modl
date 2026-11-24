package gg.modl.backend.database;

import static gg.modl.backend.database.jooq.Tables.AUDIT_LOGS;
import static gg.modl.backend.database.jooq.Tables.CHAT_LOGS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import gg.modl.backend.database.jooq.tables.records.AuditLogsRecord;
import gg.modl.backend.database.jooq.tables.records.ChatLogsRecord;
import gg.modl.backend.database.sql.SqlDatabaseProperties;
import gg.modl.backend.support.sql.DialectTarget;
import gg.modl.backend.support.sql.SqlContractTest;
import gg.modl.backend.support.sql.SqlTestDatabase;
import gg.modl.backend.support.sql.TestDialects;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.io.TempDir;

class SequencerContractTest {
    private static final List<Duration> CLOCK_STEPS = List.of(
        Duration.ofMinutes(10), Duration.ofMinutes(-30), Duration.ofSeconds(1), Duration.ofHours(-2),
        Duration.ofHours(3), Duration.ofMillis(-1), Duration.ofMinutes(45), Duration.ofDays(1), Duration.ofHours(-20));

    @TempDir
    Path dataDirectory;

    @SqlContractTest
    void idsKeepIncreasingWhenTheClockStepsBack(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory)) {
            long previous = database.sequencer().next();
            for (Duration step : CLOCK_STEPS) {
                database.clock().advance(step);
                long next = database.sequencer().next();
                assertTrue(next > previous, "id " + next + " after a step of " + step + " is not above " + previous);
                previous = next;
            }
        }
    }

    @SqlContractTest
    void aRestartSeedsAboveTheHighestStoredId(DialectTarget target) {
        SqlDatabaseProperties sameDatabase = TestDialects.freshDatabase(target);
        long storedId = Sequencer.floorAt(KernelRows.NOW.plus(Duration.ofDays(30)));
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory, sameDatabase, WriteAttemptHook.NONE)) {
            KernelRows.insert(database, new AuditLogsRecord(storedId, "info", "system", "from the future", null, true, KernelRows.NOW));
        }

        try (SqlTestDatabase restarted = SqlTestDatabase.start(target, dataDirectory, sameDatabase, WriteAttemptHook.NONE)) {
            assertEquals(storedId, restarted.dsl().select(AUDIT_LOGS.ID).from(AUDIT_LOGS).fetchSingle(AUDIT_LOGS.ID));
            assertTrue(restarted.sequencer().next() > storedId);
        }
    }

    @SqlContractTest
    void aRetentionCutNeverSelectsARowCreatedAtOrAfterTheCutoff(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory)) {
            Instant cutoff = KernelRows.NOW.plus(Duration.ofHours(2));
            List<ChatLogsRecord> rows = new ArrayList<>();
            rows.add(chatLineNow(database, rows.size()));
            for (Duration step : CLOCK_STEPS) {
                database.clock().advance(step);
                rows.add(chatLineNow(database, rows.size()));
            }
            database.clock().advance(Duration.between(database.clock().instant(), cutoff));
            rows.add(chatLineNow(database, rows.size()));
            KernelRows.insertAll(database, rows);

            List<Instant> selected = database.dsl().select(CHAT_LOGS.SENT_AT).from(CHAT_LOGS)
                .where(CHAT_LOGS.ID.lt(Sequencer.floorAt(cutoff)))
                .fetch(CHAT_LOGS.SENT_AT);

            assertFalse(selected.isEmpty());
            assertTrue(selected.stream().allMatch(sentAt -> sentAt.isBefore(cutoff)), "selected " + selected);
            assertTrue(rows.stream().anyMatch(row -> !row.getSentAt().isBefore(cutoff)));
        }
    }

    private static ChatLogsRecord chatLineNow(SqlTestDatabase database, int line) {
        return new ChatLogsRecord(database.sequencer().next(), "p1", "Steve", "line " + line, "lobby", database.clock().instant());
    }
}

package gg.modl.backend.retention.repository;

import static gg.modl.backend.database.jooq.Tables.AUDIT_LOGS;
import static gg.modl.backend.database.jooq.Tables.CHAT_LOGS;
import static gg.modl.backend.database.jooq.Tables.COMMAND_LOGS;
import static org.assertj.core.api.Assertions.assertThat;

import gg.modl.backend.database.Sequencer;
import gg.modl.backend.database.jooq.tables.records.AuditLogsRecord;
import gg.modl.backend.log.repository.ChatLogRow;
import gg.modl.backend.log.repository.CommandLogRow;
import gg.modl.backend.log.repository.JooqAuditLogRepository;
import gg.modl.backend.log.repository.JooqChatLogRepository;
import gg.modl.backend.log.repository.JooqCommandLogRepository;
import gg.modl.backend.support.sql.DialectTarget;
import gg.modl.backend.support.sql.SqlContractTest;
import gg.modl.backend.support.sql.SqlTestDatabase;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.stream.LongStream;
import org.junit.jupiter.api.io.TempDir;

class AgeRetainedRecordsContractTest {
    private static final Instant CUTOFF = SqlTestDatabase.START;
    private static final String PLAYER = "player0000000001";

    @TempDir
    Path directory;

    @SqlContractTest
    void onlyIdsBelowTheCutoffFloorAreDeletedOldestFirstInBoundedBatches(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, directory)) {
            long floor = Sequencer.floorAt(CUTOFF);
            JooqChatLogRepository chat = new JooqChatLogRepository(database.dsl(), database.unitOfWork());
            chat.insertAll(List.of(chatLine(floor + 1), chatLine(floor - 1), chatLine(7), chatLine(floor), chatLine(5), chatLine(6)));

            assertThat(chat.deleteOldestBatch(CUTOFF, 2)).isEqualTo(2);
            assertThat(chatIds(database)).containsExactly(7L, floor - 1, floor, floor + 1);
            assertThat(chat.deleteOldestBatch(CUTOFF, 2)).isEqualTo(2);
            assertThat(chat.deleteOldestBatch(CUTOFF, 2)).isZero();
            assertThat(chatIds(database)).containsExactly(floor, floor + 1);
        }
    }

    @SqlContractTest
    void theClientTimestampIsNeverARetentionPredicate(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, directory)) {
            long floor = Sequencer.floorAt(CUTOFF);
            JooqCommandLogRepository commands = new JooqCommandLogRepository(database.dsl(), database.unitOfWork());
            commands.insertAll(List.of(
                new CommandLogRow(floor - 2, PLAYER, "Steve", "/past", "lobby", CUTOFF.minusSeconds(1)),
                new CommandLogRow(floor - 1, PLAYER, "Steve", "/future", "lobby", CUTOFF.plus(Duration.ofDays(365))),
                new CommandLogRow(floor, PLAYER, "Steve", "/backdated", "lobby", Instant.EPOCH)));

            assertThat(commands.deleteOldestBatch(CUTOFF, 10)).isEqualTo(2);
            assertThat(database.dsl().select(COMMAND_LOGS.COMMAND).from(COMMAND_LOGS).fetch(COMMAND_LOGS.COMMAND))
                .containsExactly("/backdated");
        }
    }

    @SqlContractTest
    void auditEntriesFollowTheSameRule(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, directory)) {
            long floor = Sequencer.floorAt(CUTOFF);
            database.unitOfWork().write(() -> {
                database.dsl().insertInto(AUDIT_LOGS)
                    .set(new AuditLogsRecord(floor - 1, "info", "system", "expired", null, CUTOFF.plusSeconds(1)))
                    .execute();
                database.dsl().insertInto(AUDIT_LOGS)
                    .set(new AuditLogsRecord(floor, "info", "system", "retained", null, CUTOFF.minusSeconds(1)))
                    .execute();
            });
            JooqAuditLogRepository audit = new JooqAuditLogRepository(
                database.dsl(), database.unitOfWork(), database.sequencer(), database.jsonColumns());

            assertThat(audit.deleteOldestBatch(CUTOFF, 10)).isEqualTo(1);
            assertThat(database.dsl().select(AUDIT_LOGS.DESCRIPTION).from(AUDIT_LOGS).fetch(AUDIT_LOGS.DESCRIPTION))
                .containsExactly("retained");
        }
    }

    @SqlContractTest
    void aBatchLargerThanOneInClauseChunkIsDeletedWhole(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, directory)) {
            JooqChatLogRepository chat = new JooqChatLogRepository(database.dsl(), database.unitOfWork());
            chat.insertAll(LongStream.rangeClosed(1, 750).mapToObj(AgeRetainedRecordsContractTest::chatLine).toList());

            assertThat(chat.deleteOldestBatch(CUTOFF, 1_000)).isEqualTo(750);
            assertThat(database.dsl().fetchCount(CHAT_LOGS)).isZero();
        }
    }

    private static ChatLogRow chatLine(long id) {
        return new ChatLogRow(id, PLAYER, "Steve", "line", "lobby", CUTOFF);
    }

    private static List<Long> chatIds(SqlTestDatabase database) {
        return database.dsl().select(CHAT_LOGS.ID).from(CHAT_LOGS).orderBy(CHAT_LOGS.ID).fetch(CHAT_LOGS.ID);
    }
}

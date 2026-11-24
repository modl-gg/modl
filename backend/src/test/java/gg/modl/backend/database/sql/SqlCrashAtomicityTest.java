package gg.modl.backend.database.sql;

import static gg.modl.backend.database.jooq.Tables.AUDIT_LOGS;
import static gg.modl.backend.database.jooq.Tables.PUNISHMENTS;
import static gg.modl.backend.database.jooq.Tables.PUNISHMENT_EVIDENCE;
import static gg.modl.backend.database.jooq.Tables.PUNISHMENT_NOTES;
import static gg.modl.backend.database.jooq.Tables.PUNISHMENT_TICKETS;
import static gg.modl.backend.database.jooq.Tables.TICKETS;
import static gg.modl.backend.database.jooq.Tables.TICKET_REPLIES;
import static org.assertj.core.api.Assertions.assertThat;

import gg.modl.backend.database.jooq.tables.records.PunishmentsRecord;
import gg.modl.backend.database.jooq.tables.records.TicketsRecord;
import gg.modl.backend.player.data.punishment.PunishmentState;
import gg.modl.backend.support.sql.SqlTestDatabase;
import gg.modl.backend.support.sql.TestDialects;
import gg.modl.backend.ticket.data.TicketStatus;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Random;
import org.jooq.DSLContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SqlCrashAtomicityTest {
    private static final int RANDOM_KILLS = 12;
    private static final int LATEST_KILL_MILLIS = 20;
    private static final long SEED = 20_261_122L;

    @TempDir
    Path directory;

    @Test
    void aKillBeforeTheCommitLeavesNothingOfThePunishmentAndTheTicketOpen() throws Exception {
        CrashingEngineProcess.killAfter(directory, CrashWorkload.UNCOMMITTED, "held", CrashWorkload.UNCOMMITTED_WRITES, Duration.ZERO);

        try (SqlTestDatabase database = SqlTestDatabase.start(TestDialects.SQLITE, directory)) {
            DSLContext dsl = database.dsl();
            assertThat(dsl.fetchCount(PUNISHMENTS)).isZero();
            assertThat(dsl.fetchCount(PUNISHMENT_NOTES)).isZero();
            assertThat(dsl.fetchCount(PUNISHMENT_EVIDENCE)).isZero();
            assertThat(dsl.fetchCount(PUNISHMENT_TICKETS)).isZero();
            assertThat(dsl.fetchCount(TICKET_REPLIES)).isZero();
            assertThat(dsl.fetchCount(AUDIT_LOGS)).isZero();
            TicketsRecord ticket = dsl.selectFrom(TICKETS).fetchSingle();
            assertThat(ticket.getStatus()).isEqualTo(TicketStatus.OPEN.getId());
            assertThat(ticket.getIsLocked()).isFalse();
            assertThat(ticket.getReplyCount()).isZero();
        }
        CrashedDatabase.assertSound(directory);
    }

    @Test
    void killsAtRandomMomentsNeverLeaveAPartialAggregate() throws Exception {
        Random random = new Random(SEED);
        for (int kill = 0; kill < RANDOM_KILLS; kill++) {
            Duration delay = Duration.ofMillis(random.nextInt(LATEST_KILL_MILLIS + 1));
            String acknowledged = CrashingEngineProcess.killAfter(directory, CrashWorkload.CONTINUOUS, "run" + kill,
                CrashWorkload.COMMITTED, delay);

            try (SqlTestDatabase database = SqlTestDatabase.start(TestDialects.SQLITE, directory)) {
                assertThat(database.dsl().fetchExists(PUNISHMENTS, PUNISHMENTS.ID.eq(acknowledged)))
                    .as("%s, killed %s after its acknowledgement", acknowledged, delay)
                    .isTrue();
                assertWholeAggregates(database.dsl());
            }
            CrashedDatabase.assertSound(directory);
        }
    }

    private static void assertWholeAggregates(DSLContext dsl) {
        List<PunishmentsRecord> punishments = dsl.selectFrom(PUNISHMENTS).fetch();
        for (PunishmentsRecord punishment : punishments) {
            String id = punishment.getId();
            assertThat(dsl.fetchCount(PUNISHMENT_NOTES, PUNISHMENT_NOTES.PUNISHMENT_ID.eq(id))).isEqualTo(CrashWorkload.NOTES);
            assertThat(dsl.fetchCount(PUNISHMENT_EVIDENCE, PUNISHMENT_EVIDENCE.PUNISHMENT_ID.eq(id))).isEqualTo(CrashWorkload.EVIDENCE);
            assertThat(dsl.fetchCount(PUNISHMENT_TICKETS, PUNISHMENT_TICKETS.PUNISHMENT_ID.eq(id))).isEqualTo(1);
            assertThat(dsl.fetchCount(AUDIT_LOGS, AUDIT_LOGS.DESCRIPTION.eq(id))).isEqualTo(1);
            assertThat(punishment.getActiveUntilAt()).isEqualTo(PunishmentState.MAX);
            assertThat(punishment.getEnforcementCategory()).isEqualTo("BAN");
        }
        for (TicketsRecord ticket : dsl.selectFrom(TICKETS).fetch()) {
            boolean punished = punishments.stream().anyMatch(punishment -> punishment.getId().equals(ticket.getId()));
            assertThat(ticket.getIsLocked()).isEqualTo(punished);
            assertThat(ticket.getStatus()).isEqualTo((punished ? TicketStatus.CLOSED : TicketStatus.OPEN).getId());
            assertThat(ticket.getReplyCount())
                .isEqualTo(dsl.fetchCount(TICKET_REPLIES, TICKET_REPLIES.TICKET_ID.eq(ticket.getId())))
                .isEqualTo(punished ? CrashWorkload.REPLIES : 0);
        }
    }
}

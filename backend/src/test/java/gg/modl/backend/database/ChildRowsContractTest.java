package gg.modl.backend.database;

import static gg.modl.backend.database.jooq.Tables.PLAYERS;
import static gg.modl.backend.database.jooq.Tables.PLAYER_NOTES;
import static gg.modl.backend.database.jooq.Tables.PUNISHMENTS;
import static gg.modl.backend.database.jooq.Tables.PUNISHMENT_MODIFICATIONS;
import static gg.modl.backend.database.jooq.Tables.TICKETS;
import static gg.modl.backend.database.jooq.Tables.TICKET_REPLIES;
import static org.junit.jupiter.api.Assertions.assertEquals;

import gg.modl.backend.database.jooq.tables.records.PlayerNotesRecord;
import gg.modl.backend.database.jooq.tables.records.PunishmentModificationsRecord;
import gg.modl.backend.database.jooq.tables.records.TicketRepliesRecord;
import gg.modl.backend.support.sql.DialectTarget;
import gg.modl.backend.support.sql.SqlContractTest;
import gg.modl.backend.support.sql.SqlTestDatabase;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntConsumer;
import java.util.stream.IntStream;
import org.junit.jupiter.api.io.TempDir;

class ChildRowsContractTest {
    private static final int THREADS = 8;
    private static final int APPENDS_PER_THREAD = 5;
    private static final int APPENDS = THREADS * APPENDS_PER_THREAD;
    private static final long APPEND_SECONDS = 60;
    private static final String PLAYER = "p1";
    private static final String TICKET = "t1";
    private static final String PUNISHMENT = "pu1";

    @TempDir
    Path dataDirectory;

    @SqlContractTest
    void concurrentRepliesToOneTicketGetDistinctSequencesWithoutRetries(DialectTarget target) throws Exception {
        RetriedAttempts retries = new RetriedAttempts();
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory, retries)) {
            KernelRows.insert(database, KernelRows.ticket(TICKET));

            appendConcurrently(append -> database.unitOfWork().write(() -> database.childRows().append(
                () -> lockTicketByCountingTheReply(database),
                TICKET_REPLIES, TICKET_REPLIES.TICKET_ID, TICKET_REPLIES.SEQ, reply(append))));

            assertEquals(expectedSequences(), database.dsl().select(TICKET_REPLIES.SEQ).from(TICKET_REPLIES)
                .where(TICKET_REPLIES.TICKET_ID.eq(TICKET)).orderBy(TICKET_REPLIES.SEQ).fetch(TICKET_REPLIES.SEQ));
            assertEquals(APPENDS, database.dsl().fetchSingle(TICKETS, TICKETS.ID.eq(TICKET)).getReplyCount());
            assertEquals(0, retries.count());
        }
    }

    @SqlContractTest
    void concurrentModificationsOfOnePunishmentGetDistinctSequencesWithoutRetries(DialectTarget target) throws Exception {
        RetriedAttempts retries = new RetriedAttempts();
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory, retries)) {
            KernelRows.insert(database, KernelRows.player(PLAYER), KernelRows.punishment(PUNISHMENT, PLAYER));

            appendConcurrently(append -> database.unitOfWork().write(() -> database.childRows().append(
                () -> lockPunishmentByBumpingItsVersion(database),
                PUNISHMENT_MODIFICATIONS, PUNISHMENT_MODIFICATIONS.PUNISHMENT_ID, PUNISHMENT_MODIFICATIONS.SEQ, modification(append))));

            assertEquals(expectedSequences(), database.dsl().select(PUNISHMENT_MODIFICATIONS.SEQ).from(PUNISHMENT_MODIFICATIONS)
                .where(PUNISHMENT_MODIFICATIONS.PUNISHMENT_ID.eq(PUNISHMENT)).orderBy(PUNISHMENT_MODIFICATIONS.SEQ)
                .fetch(PUNISHMENT_MODIFICATIONS.SEQ));
            assertEquals(APPENDS, database.dsl().fetchSingle(PUNISHMENTS, PUNISHMENTS.ID.eq(PUNISHMENT)).getVersion());
            assertEquals(0, retries.count());
        }
    }

    @SqlContractTest
    void concurrentNotesOnOnePlayerGetDistinctSequencesWithoutRetries(DialectTarget target) throws Exception {
        RetriedAttempts retries = new RetriedAttempts();
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory, retries)) {
            KernelRows.insert(database, KernelRows.player(PLAYER));

            appendConcurrently(append -> database.unitOfWork().write(() -> database.childRows().append(
                () -> database.childRows().lockRow(PLAYERS, PLAYERS.ID, PLAYER),
                PLAYER_NOTES, PLAYER_NOTES.PLAYER_ID, PLAYER_NOTES.SEQ, note(append))));

            assertEquals(expectedSequences(), database.dsl().select(PLAYER_NOTES.SEQ).from(PLAYER_NOTES)
                .where(PLAYER_NOTES.PLAYER_ID.eq(PLAYER)).orderBy(PLAYER_NOTES.SEQ).fetch(PLAYER_NOTES.SEQ));
            assertEquals(0, retries.count());
        }
    }

    private static void lockTicketByCountingTheReply(SqlTestDatabase database) {
        database.dsl().update(TICKETS)
            .set(TICKETS.REPLY_COUNT, TICKETS.REPLY_COUNT.plus(1))
            .set(TICKETS.UPDATED_AT, database.clock().instant())
            .where(TICKETS.ID.eq(TICKET))
            .execute();
    }

    private static void lockPunishmentByBumpingItsVersion(SqlTestDatabase database) {
        database.dsl().update(PUNISHMENTS)
            .set(PUNISHMENTS.VERSION, PUNISHMENTS.VERSION.plus(1))
            .set(PUNISHMENTS.MODIFIED_AT, database.clock().instant())
            .where(PUNISHMENTS.ID.eq(PUNISHMENT))
            .execute();
    }

    private static TicketRepliesRecord reply(int append) {
        TicketRepliesRecord reply = new TicketRepliesRecord();
        reply.setTicketId(TICKET);
        reply.setId("reply-" + append);
        reply.setContent("reply " + append);
        reply.setCreatedAt(KernelRows.NOW);
        return reply;
    }

    private static PunishmentModificationsRecord modification(int append) {
        PunishmentModificationsRecord modification = new PunishmentModificationsRecord();
        modification.setPunishmentId(PUNISHMENT);
        modification.setId("modification-" + append);
        modification.setType("DURATION_CHANGE");
        modification.setReason("change " + append);
        modification.setCreatedAt(KernelRows.NOW);
        return modification;
    }

    private static PlayerNotesRecord note(int append) {
        PlayerNotesRecord note = new PlayerNotesRecord();
        note.setPlayerId(PLAYER);
        note.setId("note-" + append);
        note.setBody("note " + append);
        note.setCreatedAt(KernelRows.NOW);
        return note;
    }

    private static List<Integer> expectedSequences() {
        return IntStream.rangeClosed(1, APPENDS).boxed().toList();
    }

    private static void appendConcurrently(IntConsumer append) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService threads = Executors.newFixedThreadPool(THREADS);
        try {
            List<Future<?>> appenders = new ArrayList<>();
            for (int thread = 0; thread < THREADS; thread++) {
                int firstAppend = thread * APPENDS_PER_THREAD;
                appenders.add(threads.submit(() -> {
                    start.await();
                    for (int offset = 0; offset < APPENDS_PER_THREAD; offset++) {
                        append.accept(firstAppend + offset);
                    }
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
    }

    private static final class RetriedAttempts implements WriteAttemptHook {
        private final AtomicInteger retried = new AtomicInteger();

        @Override
        public void beforeCommit(int attempt) {
            if (attempt > 1) {
                retried.incrementAndGet();
            }
        }

        int count() {
            return retried.get();
        }
    }
}

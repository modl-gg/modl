package gg.modl.backend.database;

import static gg.modl.backend.database.jooq.Tables.SETTINGS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import gg.modl.backend.support.sql.DialectTarget;
import gg.modl.backend.support.sql.RetryInjection;
import gg.modl.backend.support.sql.SqlContractTest;
import gg.modl.backend.support.sql.SqlTestDatabase;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.io.TempDir;

class UnitOfWorkContractTest {
    private static final long BARRIER_SECONDS = 10;
    private static final long DEADLOCK_PAIR_SECONDS = 30;

    @TempDir
    Path dataDirectory;

    @SqlContractTest
    void anInternalConflictIsRetriedAndTheUnitThenCommits(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory)) {
            AtomicInteger attempts = new AtomicInteger();

            database.unitOfWork().write(() -> {
                int attempt = attempts.incrementAndGet();
                insertSettings(database, "general");
                if (attempt == 1) {
                    throw new ConcurrentUpdateException("version moved");
                }
            });

            assertEquals(2, attempts.get());
            assertEquals(1, database.dsl().fetchCount(SETTINGS));
        }
    }

    @SqlContractTest
    void aStaleClientVersionRunsExactlyOneAttempt(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory)) {
            AtomicInteger attempts = new AtomicInteger();
            StaleClientVersionException stale = new StaleClientVersionException("old version", 4);

            StaleClientVersionException thrown = assertThrows(StaleClientVersionException.class, () -> database.unitOfWork().write(() -> {
                attempts.incrementAndGet();
                throw stale;
            }));

            assertSame(stale, thrown);
            assertEquals(1, attempts.get());
        }
    }

    @SqlContractTest
    void retryInjectionRunsEveryWriteTwiceAndFiresAfterCommitOnceFromTheCommittedAttempt(DialectTarget target) {
        RetryInjection injection = new RetryInjection();
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory, injection)) {
            AtomicInteger attempts = new AtomicInteger();
            List<Integer> effects = new CopyOnWriteArrayList<>();

            database.unitOfWork().write(() -> {
                int attempt = attempts.incrementAndGet();
                insertSettings(database, "general");
                database.unitOfWork().afterCommit(() -> effects.add(attempt));
            });
            database.unitOfWork().write(() -> insertSettings(database, "second"));

            assertEquals(2, attempts.get());
            assertEquals(List.of(2), effects);
            assertEquals(2, injection.injected());
            assertEquals(2, database.dsl().fetchCount(SETTINGS));
        }
    }

    @SqlContractTest
    void anAfterCommitEffectOfARetriedAttemptIsDiscarded(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory)) {
            AtomicInteger attempts = new AtomicInteger();
            List<Integer> effects = new CopyOnWriteArrayList<>();

            database.unitOfWork().write(() -> {
                int attempt = attempts.incrementAndGet();
                database.unitOfWork().afterCommit(() -> effects.add(attempt));
                if (attempt == 1) {
                    throw new ConcurrentUpdateException("version moved");
                }
            });

            assertEquals(List.of(2), effects);
        }
    }

    @SqlContractTest
    void anExceptionRollsTheUnitBackAndFiresNoEffect(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory)) {
            List<String> effects = new CopyOnWriteArrayList<>();
            IllegalStateException failure = new IllegalStateException("validation failed");

            IllegalStateException thrown = assertThrows(IllegalStateException.class, () -> database.unitOfWork().write(() -> {
                insertSettings(database, "general");
                database.unitOfWork().afterCommit(() -> effects.add("fired"));
                throw failure;
            }));

            assertSame(failure, thrown);
            assertEquals(0, database.dsl().fetchCount(SETTINGS));
            assertTrue(effects.isEmpty());
        }
    }

    @SqlContractTest
    void aWriteInsideAReadThrows(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory)) {
            assertThrows(IllegalStateException.class, () -> database.unitOfWork().read(() ->
                database.unitOfWork().write(() -> insertSettings(database, "general"))));
            assertEquals(0, database.dsl().fetchCount(SETTINGS));
        }
    }

    @SqlContractTest
    void aReportInsideAReadThrows(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory)) {
            assertThrows(IllegalStateException.class, () -> database.unitOfWork().read(() ->
                database.unitOfWork().report(() -> database.dsl().fetchCount(SETTINGS))));
        }
    }

    @SqlContractTest
    void aReportInsideAWriteThrows(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory)) {
            assertThrows(IllegalStateException.class, () -> database.unitOfWork().write(() ->
                database.unitOfWork().report(() -> database.dsl().fetchCount(SETTINGS))));
        }
    }

    @SqlContractTest
    void aReadInsideAWriteJoinsItAndSeesTheUncommittedRow(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory)) {
            int seen = database.unitOfWork().write(() -> {
                insertSettings(database, "general");
                return database.unitOfWork().read(() -> database.dsl().fetchCount(SETTINGS, SETTINGS.TYPE.eq("general")));
            });

            assertEquals(1, seen);
        }
    }

    @SqlContractTest
    void dataChangesOutsideAWriteUnitAreRejected(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory)) {
            KernelRows.insert(database, KernelRows.settings("general"));

            assertThrows(IllegalStateException.class, () -> insertSettings(database, "outside"));
            assertThrows(IllegalStateException.class, () -> database.dsl().update(SETTINGS).set(SETTINGS.VERSION, 9L).execute());
            assertThrows(IllegalStateException.class, () -> database.dsl().deleteFrom(SETTINGS).execute());
            assertThrows(IllegalStateException.class, () -> database.unitOfWork().read(() -> database.dsl().deleteFrom(SETTINGS).execute()));
            assertEquals(0L, database.dsl().fetchSingle(SETTINGS, SETTINGS.TYPE.eq("general")).getVersion());
        }
    }

    @SqlContractTest
    void readsOutsideAnyUnitWork(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory)) {
            KernelRows.insert(database, KernelRows.settings("general"), KernelRows.settings("second"));

            assertEquals(2, database.dsl().fetchCount(SETTINGS));
            assertEquals("{}", database.dsl().fetchSingle(SETTINGS, SETTINGS.TYPE.eq("second")).getDataJson());
        }
    }

    @SqlContractTest
    void aDeadlockedPairOfWritesBothCommitAfterTheKernelRetry(DialectTarget target) throws Exception {
        assumeTrue(target.dialect().isExternal(), "SQLite has a single writer and cannot deadlock");
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory)) {
            KernelRows.insert(database, KernelRows.settings("a"), KernelRows.settings("b"));
            CyclicBarrier bothHoldOneRow = new CyclicBarrier(2);
            AtomicInteger bodies = new AtomicInteger();
            ExecutorService threads = Executors.newFixedThreadPool(2);
            try {
                Future<?> forward = threads.submit(() -> bumpInOrder(database, bothHoldOneRow, bodies, "a", "b"));
                Future<?> backward = threads.submit(() -> bumpInOrder(database, bothHoldOneRow, bodies, "b", "a"));
                forward.get(DEADLOCK_PAIR_SECONDS, TimeUnit.SECONDS);
                backward.get(DEADLOCK_PAIR_SECONDS, TimeUnit.SECONDS);
            } finally {
                threads.shutdownNow();
            }

            assertEquals(3, bodies.get());
            assertEquals(2L, version(database, "a"));
            assertEquals(2L, version(database, "b"));
        }
    }

    private static void bumpInOrder(SqlTestDatabase database, CyclicBarrier bothHoldOneRow, AtomicInteger bodies,
                                    String first, String second) {
        AtomicInteger ownAttempts = new AtomicInteger();
        database.unitOfWork().write(() -> {
            bodies.incrementAndGet();
            bumpVersion(database, first);
            if (ownAttempts.incrementAndGet() == 1) {
                awaitBoth(bothHoldOneRow);
            }
            bumpVersion(database, second);
        });
    }

    private static void awaitBoth(CyclicBarrier barrier) {
        try {
            barrier.await(BARRIER_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        } catch (BrokenBarrierException | TimeoutException e) {
            throw new IllegalStateException("The other writer never took its first row", e);
        }
    }

    private static void bumpVersion(SqlTestDatabase database, String type) {
        database.dsl().update(SETTINGS).set(SETTINGS.VERSION, SETTINGS.VERSION.plus(1L)).where(SETTINGS.TYPE.eq(type)).execute();
    }

    private static long version(SqlTestDatabase database, String type) {
        return database.dsl().fetchSingle(SETTINGS, SETTINGS.TYPE.eq(type)).getVersion();
    }

    private static int insertSettings(SqlTestDatabase database, String type) {
        return database.dsl().insertInto(SETTINGS).set(KernelRows.settings(type)).execute();
    }
}

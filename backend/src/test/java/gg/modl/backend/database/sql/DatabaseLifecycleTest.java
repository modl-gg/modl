package gg.modl.backend.database.sql;

import static gg.modl.backend.database.jooq.Tables.SETTINGS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mockConstruction;

import com.zaxxer.hikari.HikariDataSource;
import gg.modl.backend.database.DatabaseUnavailableException;
import gg.modl.backend.database.TransactionalUnitOfWork;
import gg.modl.backend.database.jooq.tables.records.SettingsRecord;
import gg.modl.backend.support.sql.SqlTestDatabase;
import gg.modl.backend.support.sql.TestDialects;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedConstruction;

class DatabaseLifecycleTest {
    private static final Duration CLOSE_BOUND = Duration.ofSeconds(12);
    private static final Duration SHORT_DRAIN_BUDGET = DatabaseLifecycle.CANCEL_MARGIN.plusMillis(50);
    private static final Duration SHORT_WAIT = Duration.ofMillis(500);
    private static final Duration AWAIT_TIMEOUT = Duration.ofSeconds(30);
    private static final int BACKUP_MEBIBYTES = 256;
    private static final String HELD_SETTINGS = "held";
    private static final String HOLD_WRITER_SQL = "WITH RECURSIVE n(x) AS (SELECT 1 UNION ALL SELECT x + 1 FROM n "
        + "WHERE x < 4000000000) SELECT count(*) FROM n";

    @TempDir
    Path dataDirectory;

    @Test
    void closeWhileAUnitHoldsTheWriterCancelsItsStatementAndClosesBothPoolsWithinTheBound() {
        CompletableFuture<Long> unit;
        HikariDataSource writer;
        HikariDataSource reader;
        Duration close;
        try (SqlTestDatabase database = start()) {
            writer = (HikariDataSource) pools(database).writer();
            reader = (HikariDataSource) pools(database).reader();
            unit = holdWriter(database);
            awaitRunningStatements(database, 1);

            close = timed(database::close);
        }

        System.out.println("MEASURE close with the writer held past the drain budget: " + close.toMillis() + " ms");
        assertTrue(close.compareTo(CLOSE_BOUND) < 0, close.toString());
        assertTrue(close.compareTo(DatabaseLifecycle.DRAIN_BUDGET.minus(DatabaseLifecycle.CANCEL_MARGIN)) >= 0, close.toString());
        CompletionException failure = assertThrows(CompletionException.class, unit::join);
        assertInstanceOf(DatabaseUnavailableException.class, failure.getCause(), CauseChain.messages(failure));
        assertTrue(CauseChain.messages(failure).contains("SQLITE_INTERRUPT"), CauseChain.messages(failure));
        assertTrue(writer.isClosed());
        assertTrue(reader.isClosed());
        assertEquals(0, settingsRowsAfterRestart());
    }

    @Test
    void drainCancelsARunningSnapshotAndRollsBackTheInFlightWrite() throws SQLException {
        Path copy = dataDirectory.resolve("nightly.db");
        CompletableFuture<Void> backup;
        CompletableFuture<Long> unit;
        Duration drain;
        Duration close;
        try (SqlTestDatabase database = start()) {
            BulkPayload.fill(pools(database).writer(), BACKUP_MEBIBYTES);
            backup = backupJob(database, copy);
            unit = holdWriter(database);
            awaitRunningStatements(database, 2);

            drain = timed(() -> shortDrain(database).drain());
            close = timed(database::close);
        }

        System.out.println("MEASURE drain with a running snapshot and write (budget " + SHORT_DRAIN_BUDGET.toMillis() + " ms): "
            + drain.toMillis() + " ms, then close: " + close.toMillis() + " ms");
        CompletionException backupFailure = assertThrows(CompletionException.class, backup::join);
        assertTrue(CauseChain.anyOfClass(backupFailure, "SQLiteException"), CauseChain.messages(backupFailure));
        assertFalse(Files.exists(copy));
        CompletionException unitFailure = assertThrows(CompletionException.class, unit::join);
        assertInstanceOf(DatabaseUnavailableException.class, unitFailure.getCause(), CauseChain.messages(unitFailure));
        assertTrue(CauseChain.messages(unitFailure).contains("SQLITE_INTERRUPT"), CauseChain.messages(unitFailure));
        assertEquals(0, settingsRowsAfterRestart());
        assertTrue(drain.plus(close).compareTo(CLOSE_BOUND) < 0);
    }

    @Test
    void whileDrainingAReadOutsideAnyUnitIsRefusedAndTheRunningUnitStillCommits() throws InterruptedException {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch finish = new CountDownLatch(1);
        try (SqlTestDatabase database = start()) {
            CompletableFuture<Void> unit = CompletableFuture.runAsync(() -> database.unitOfWork().write(() -> {
                insertSettings(database, HELD_SETTINGS);
                entered.countDown();
                awaitQuietly(finish);
            }));
            entered.await();
            CompletableFuture<Void> closing = CompletableFuture.runAsync(database::close);
            awaitCondition(() -> database.fence().state() == InstanceFence.State.DRAINING);

            assertThrows(DatabaseUnavailableException.class, () -> database.dsl().fetchCount(SETTINGS));

            finish.countDown();
            unit.join();
            closing.join();
        }
        assertEquals(1, settingsRowsAfterRestart());
    }

    @Test
    void aStartThatFailsDuringRefreshReleasesTheLockSoTheNextStartSucceeds() {
        try (MockedConstruction<DurabilityAdvisor> failing = mockConstruction(DurabilityAdvisor.class, (advisor, context) -> {
            throw new IllegalStateException("deliberate start failure");
        })) {
            RuntimeException failure = assertThrows(RuntimeException.class, this::start);
            assertTrue(CauseChain.messages(failure).contains("deliberate start failure"), CauseChain.messages(failure));
        }

        assertNull(System.getProperty(marker()));
        try (SqlTestDatabase database = start()) {
            assertEquals(InstanceFence.State.SERVING, database.fence().state());
        }
    }

    @Test
    void aCleanCloseReleasesTheLockOnlyWithNoTrackedConnectionLeft() {
        ConnectionTracker tracker;
        try (SqlTestDatabase database = start()) {
            tracker = database.connectionTracker();
            insertSettingsInUnit(database, HELD_SETTINGS);
            assertNotNull(System.getProperty(marker()));
        }

        assertEquals(0, tracker.openConnections());
        assertNull(System.getProperty(marker()));
    }

    @Test
    void aConnectionStillOpenAtCloseKeepsTheLockAndItsMarker() throws SQLException {
        ConnectionTracker tracker;
        HikariDataSource writer;
        HikariDataSource reader;
        int retainedBefore = RetainedLocks.size();
        SqlTestDatabase database = start();
        try (Connection held = pools(database).writer().getConnection()) {
            tracker = database.connectionTracker();
            writer = (HikariDataSource) pools(database).writer();
            reader = (HikariDataSource) pools(database).reader();

            database.close();

            assertTrue(tracker.openConnections() > 0);
            assertNotNull(System.getProperty(marker()));
            assertEquals(retainedBefore + 1, RetainedLocks.size());
            assertFalse(writer.isClosed());
            assertTrue(reader.isClosed());
            IllegalStateException refused = assertThrows(IllegalStateException.class,
                () -> FileInstanceLock.acquire(dataDirectory.resolve("database"),
                    new LockRelease(new ConnectionTracker(), ClassLoader.getPlatformClassLoader()), SHORT_WAIT));
            assertTrue(refused.getMessage().contains("has not released its database"), refused.getMessage());
        }
        writer.close();
    }

    private SqlTestDatabase start() {
        return SqlTestDatabase.start(TestDialects.SQLITE, dataDirectory);
    }

    private int settingsRowsAfterRestart() {
        try (SqlTestDatabase database = start()) {
            return database.dsl().fetchCount(SETTINGS);
        }
    }

    private String marker() {
        return FileInstanceLock.markerName(FileInstanceLock.lockFile(dataDirectory.resolve("database")));
    }

    private static ConnectionPools pools(SqlTestDatabase database) {
        return database.bean(ConnectionPools.class);
    }

    private static DatabaseLifecycle shortDrain(SqlTestDatabase database) {
        return new DatabaseLifecycle(database.fence(), database.bean(TransactionalUnitOfWork.class),
            database.bean(DatabaseWorkExecutor.class), database.bean(RunningStatements.class), pools(database), SHORT_DRAIN_BUDGET);
    }

    private static CompletableFuture<Long> holdWriter(SqlTestDatabase database) {
        Supplier<Long> heldWrite = () -> {
            insertSettings(database, HELD_SETTINGS);
            return database.dsl().fetchSingle(HOLD_WRITER_SQL).get(0, Long.class);
        };
        return CompletableFuture.supplyAsync(() -> database.unitOfWork().write(heldWrite));
    }

    private static CompletableFuture<Void> backupJob(SqlTestDatabase database, Path target) {
        SnapshotConnections snapshots = database.bean(SnapshotConnections.class);
        return CompletableFuture.runAsync(() -> database.bean(DatabaseWorkExecutor.class).runJob(() -> {
            try {
                snapshots.vacuumInto(target);
            } catch (SQLException e) {
                throw new IllegalStateException(e);
            }
        }));
    }

    private static void insertSettingsInUnit(SqlTestDatabase database, String type) {
        database.unitOfWork().write(() -> insertSettings(database, type));
    }

    private static void insertSettings(SqlTestDatabase database, String type) {
        database.insertIfAbsent().onPrimaryKey(SETTINGS).insert(new SettingsRecord(type, "{}", 0L, SqlTestDatabase.START));
    }

    private static void awaitRunningStatements(SqlTestDatabase database, int count) {
        RunningStatements running = database.bean(RunningStatements.class);
        awaitCondition(() -> running.size() >= count);
    }

    private static void awaitCondition(BooleanSupplier condition) {
        long deadline = System.nanoTime() + AWAIT_TIMEOUT.toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() >= deadline) {
                throw new IllegalStateException("The condition did not hold within " + AWAIT_TIMEOUT);
            }
            Thread.onSpinWait();
        }
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private static Duration timed(Runnable action) {
        long started = System.nanoTime();
        action.run();
        return Duration.ofNanos(System.nanoTime() - started);
    }
}

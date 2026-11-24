package gg.modl.backend.database;

import static gg.modl.backend.database.jooq.Tables.SETTINGS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import gg.modl.backend.database.sql.DatabaseWorkExecutor;
import gg.modl.backend.database.sql.RunningStatements;
import gg.modl.backend.support.sql.DialectTarget;
import gg.modl.backend.support.sql.SqlContractTest;
import gg.modl.backend.support.sql.SqlTestDatabase;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import org.jooq.CommonTableExpression;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Name;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.TestReporter;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.transaction.PlatformTransactionManager;

class ReportLaneContractTest {
    private static final Duration REPORT_DEADLINE = Duration.ofSeconds(2);
    private static final Duration CANCEL_SLACK = Duration.ofSeconds(2);
    private static final Duration LANE_WAIT_SLACK = Duration.ofSeconds(1);
    private static final Duration QUICK_UNIT = Duration.ofSeconds(1);
    private static final Duration STATEMENT_START_WAIT = Duration.ofSeconds(5);
    private static final long HOLDER_SECONDS = 30;
    private static final int COUNTER_ROWS = 1_000;
    private static final Name COUNTER = DSL.name("counter");
    private static final Field<Integer> COUNTER_VALUE = DSL.field(DSL.name("counter", "n"), Integer.class);

    @TempDir
    Path dataDirectory;

    @SqlContractTest
    void aSlowReportIsCancelledAtItsDeadlineAsBusy(DialectTarget target, TestReporter reporter) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory);
             DatabaseWorkExecutor work = new DatabaseWorkExecutor(1, 1)) {
            UnitOfWork lane = laneUnitOfWork(database, work);

            long started = System.nanoTime();
            assertThrows(DatabaseBusyException.class, () -> lane.report(() -> countToATrillion(database.dsl())));
            Duration elapsed = Duration.ofNanos(System.nanoTime() - started);

            reporter.publishEntry("cancel-latency-ms-after-deadline", Long.toString(elapsed.minus(REPORT_DEADLINE).toMillis()));
            assertTrue(elapsed.compareTo(REPORT_DEADLINE) >= 0, "cancelled early after " + elapsed);
            assertTrue(elapsed.compareTo(REPORT_DEADLINE.plus(CANCEL_SLACK)) < 0, "cancelled late after " + elapsed);
            assertEquals(0, database.bean(RunningStatements.class).size());
        }
    }

    @SqlContractTest
    void aSecondReportWaitsAboutTwoSecondsForABusyLaneAndIsRefusedAsBusy(DialectTarget target, TestReporter reporter)
        throws Exception {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory);
             DatabaseWorkExecutor work = new DatabaseWorkExecutor(1, 1)) {
            UnitOfWork lane = laneUnitOfWork(database, work);
            CountDownLatch holding = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            CompletableFuture<Integer> holder = CompletableFuture.supplyAsync(() -> lane.report(() -> holdLane(holding, release)));
            assertTrue(holding.await(HOLDER_SECONDS, TimeUnit.SECONDS));

            long started = System.nanoTime();
            assertThrows(DatabaseBusyException.class, () -> lane.report(() -> database.dsl().fetchCount(SETTINGS)));
            Duration waited = Duration.ofNanos(System.nanoTime() - started);
            release.countDown();

            reporter.publishEntry("lane-wait-ms", Long.toString(waited.toMillis()));
            assertTrue(waited.compareTo(ReportLane.ACQUIRE_TIMEOUT.minus(LANE_WAIT_SLACK)) > 0, "refused early after " + waited);
            assertTrue(waited.compareTo(ReportLane.ACQUIRE_TIMEOUT.plus(LANE_WAIT_SLACK)) < 0, "refused late after " + waited);
            assertEquals(1, holder.get(HOLDER_SECONDS, TimeUnit.SECONDS));
            assertEquals(0, lane.report(() -> database.dsl().fetchCount(SETTINGS)));
        }
    }

    @SqlContractTest
    void aSearchIsNotRefusedWhileAReportHoldsTheReportLane(DialectTarget target) throws Exception {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory);
             DatabaseWorkExecutor work = new DatabaseWorkExecutor(2, 1)) {
            UnitOfWork lane = laneUnitOfWork(database, work);
            CountDownLatch holding = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            CompletableFuture<Integer> holder = CompletableFuture.supplyAsync(() -> lane.report(() -> holdLane(holding, release)));
            assertTrue(holding.await(HOLDER_SECONDS, TimeUnit.SECONDS));

            Duration search = timed(() -> lane.search(() -> database.dsl().fetchCount(SETTINGS)));
            release.countDown();

            assertTrue(search.compareTo(QUICK_UNIT) < 0, "search took " + search);
            assertEquals(1, holder.get(HOLDER_SECONDS, TimeUnit.SECONDS));
        }
    }

    @SqlContractTest
    void readAndWriteUnitsStayQuickWhileASlowReportRuns(DialectTarget target, TestReporter reporter) throws Exception {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory);
             DatabaseWorkExecutor work = new DatabaseWorkExecutor(1, 1)) {
            UnitOfWork lane = laneUnitOfWork(database, work);
            CompletableFuture<Long> slowReport = CompletableFuture.supplyAsync(() -> lane.report(() -> countToATrillion(database.dsl())));
            awaitRunningStatement(database.bean(RunningStatements.class));

            Duration write = timed(() -> lane.write(() -> database.dsl().insertInto(SETTINGS).set(KernelRows.settings("general")).execute()));
            Duration read = timed(() -> lane.read(() -> database.dsl().fetchCount(SETTINGS)));
            boolean reportStillRunning = !slowReport.isDone();

            reporter.publishEntry("write-ms-while-report-runs", Long.toString(write.toMillis()));
            reporter.publishEntry("read-ms-while-report-runs", Long.toString(read.toMillis()));
            assertTrue(reportStillRunning);
            assertTrue(write.compareTo(QUICK_UNIT) < 0, "write took " + write);
            assertTrue(read.compareTo(QUICK_UNIT) < 0, "read took " + read);
            ExecutionException failure = assertThrows(ExecutionException.class, () -> slowReport.get(HOLDER_SECONDS, TimeUnit.SECONDS));
            assertInstanceOf(DatabaseBusyException.class, failure.getCause());
            assertEquals(1, database.dsl().fetchCount(SETTINGS));
        }
    }

    private static UnitOfWork laneUnitOfWork(SqlTestDatabase database, DatabaseWorkExecutor work) {
        return new TransactionalUnitOfWork(database.bean(PlatformTransactionManager.class), database.bean(ConstraintViolations.class),
            new ReadLanes(new ReportLane(1, REPORT_DEADLINE), new ReportLane(1, REPORT_DEADLINE)), work, WriteAttemptHook.NONE,
            new WritePause());
    }

    private static long countToATrillion(DSLContext dsl) {
        CommonTableExpression<?> counter = COUNTER.fields("n").as(DSL.select(DSL.inline(1))
            .unionAll(DSL.select(COUNTER_VALUE.plus(1)).from(DSL.table(COUNTER)).where(COUNTER_VALUE.lt(COUNTER_ROWS))));
        return dsl.withRecursive(counter)
            .select(DSL.count())
            .from(counter.as("a"), counter.as("b"), counter.as("c"), counter.as("d"))
            .fetchSingle(0, Long.class);
    }

    private static int holdLane(CountDownLatch holding, CountDownLatch release) {
        holding.countDown();
        try {
            assertTrue(release.await(HOLDER_SECONDS, TimeUnit.SECONDS));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
        return 1;
    }

    private static void awaitRunningStatement(RunningStatements statements) throws InterruptedException {
        long giveUp = System.nanoTime() + STATEMENT_START_WAIT.toNanos();
        while (statements.size() == 0 && System.nanoTime() < giveUp) {
            Thread.sleep(10);
        }
        assertFalse(statements.size() == 0, "the slow report never started its statement");
    }

    private static Duration timed(Runnable unit) {
        long started = System.nanoTime();
        unit.run();
        return Duration.ofNanos(System.nanoTime() - started);
    }
}

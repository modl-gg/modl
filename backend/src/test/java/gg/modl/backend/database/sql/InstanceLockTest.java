package gg.modl.backend.database.sql;

import static gg.modl.backend.database.jooq.Tables.SETTINGS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import gg.modl.backend.database.DatabaseUnavailableException;
import gg.modl.backend.database.WriteAttemptHook;
import gg.modl.backend.infrastructure.logging.OperatorNotice;
import gg.modl.backend.support.sql.DialectTarget;
import gg.modl.backend.support.sql.ExternalDialectTest;
import gg.modl.backend.support.sql.SqlTestDatabase;
import gg.modl.backend.support.sql.TestDialects;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class InstanceLockTest {
    private static final Duration SHORT_WAIT = Duration.ofMillis(750);
    private static final AdvisoryInstanceLock.Timing SHORT_TIMING =
        new AdvisoryInstanceLock.Timing(Duration.ofSeconds(2), Duration.ofMillis(250), Duration.ofSeconds(30),
            Duration.ofHours(1), true);
    private static final AdvisoryInstanceLock.Timing PATIENT_TIMING =
        new AdvisoryInstanceLock.Timing(Duration.ofSeconds(20), Duration.ofMillis(250), Duration.ofSeconds(30),
            Duration.ofHours(1), true);
    private static final String OTHER_PROCESS_REMEDY = "Another process is using";
    private static final String RESTART_REMEDY = "has not released its database";
    private static final String EXTERNAL_HELD_REMEDY = "Another modl server is using this database";

    @TempDir
    Path dataDirectory;

    @Test
    void aSecondProcessFailsClosedWhileTheFirstHoldsTheLockAndStartsOnceTheFirstIsKilled() throws InterruptedException {
        try (ChildJvm first = ChildJvm.start(InstanceHolderProcess.class, dataDirectory.toString())) {
            first.awaitLineStartingWith(InstanceHolderProcess.READY);
            assertOtherProcessesAreLockedOut();
            first.kill();
        }
        try (SqlTestDatabase database = SqlTestDatabase.start(TestDialects.SQLITE, dataDirectory)) {
            assertEquals(InstanceFence.State.SERVING, database.fence().state());
        }
    }

    @Test
    void aSecondAcquireInTheSameJvmWaitsOnTheMarkerAndFailsClosedWithoutOpeningTheLockFile() throws InterruptedException {
        FileInstanceLock first = FileInstanceLock.acquire(databaseDirectory(), releaseFor(new ConnectionTracker()), SHORT_WAIT);
        try {
            long started = System.nanoTime();
            IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> FileInstanceLock.acquire(databaseDirectory(), releaseFor(new ConnectionTracker()), SHORT_WAIT));

            assertTrue(Duration.ofNanos(System.nanoTime() - started).compareTo(SHORT_WAIT) >= 0);
            assertTrue(failure.getMessage().contains(RESTART_REMEDY), failure.getMessage());
            assertOtherProcessesAreLockedOut();
        } finally {
            first.destroy();
        }
    }

    @Test
    void releaseClearsTheMarkerSoTheNextAcquireNeedsNoWait() {
        FileInstanceLock first = FileInstanceLock.acquire(databaseDirectory(), releaseFor(new ConnectionTracker()), SHORT_WAIT);
        assertNotNull(System.getProperty(marker()));

        first.destroy();

        assertNull(System.getProperty(marker()));
        FileInstanceLock.acquire(databaseDirectory(), releaseFor(new ConnectionTracker()), Duration.ZERO).destroy();
    }

    @Test
    void aLockDestroyedWithAnOpenTrackedConnectionIsRetainedAndALaterAcquireFailsClosed() throws Exception {
        ConnectionTracker tracker = new ConnectionTracker();
        Connection open = tracker.track(dataSourceOfMockConnections(), ConnectionRole.POOL).getConnection();
        FileInstanceLock lock = FileInstanceLock.acquire(databaseDirectory(), releaseFor(tracker), SHORT_WAIT);
        int retainedBefore = RetainedLocks.size();

        lock.destroy();

        assertEquals(retainedBefore + 1, RetainedLocks.size());
        assertNotNull(System.getProperty(marker()));
        IllegalStateException failure = assertThrows(IllegalStateException.class,
            () -> FileInstanceLock.acquire(databaseDirectory(), releaseFor(new ConnectionTracker()), SHORT_WAIT));
        assertTrue(failure.getMessage().contains(RESTART_REMEDY), failure.getMessage());
        assertOtherProcessesAreLockedOut();
        open.close();
    }

    @ExternalDialectTest
    void aSecondInstanceOnTheSameExternalDatabaseFailsClosed(DialectTarget target) {
        SqlDatabaseProperties database = TestDialects.freshDatabase(target);
        try (SqlTestDatabase first = SqlTestDatabase.start(target, dataDirectory, database, WriteAttemptHook.NONE)) {
            OperatorNotice notice = mock(OperatorNotice.class);

            IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> acquireExternal(database, new InstanceFence(), notice, SHORT_TIMING));

            assertTrue(failure.getMessage().contains(EXTERNAL_HELD_REMEDY), failure.getMessage());
            verify(notice).publish(contains("waiting up to"), eq(SHORT_TIMING.acquireBudget().toSeconds()));
            assertEquals(InstanceFence.State.SERVING, first.fence().state());
        }
    }

    @ExternalDialectTest
    void aKilledLockSessionTakenByAnotherInstanceFencesOnTheNextHeartbeat(DialectTarget target) throws SQLException {
        SqlDatabaseProperties database = TestDialects.freshDatabase(target);
        try (SqlTestDatabase first = SqlTestDatabase.start(target, dataDirectory, database, WriteAttemptHook.NONE);
             ExternalLockSessions sessions = ExternalLockSessions.open(database)) {
            sessions.killHolder();
            AdvisoryInstanceLock second = acquireExternal(database, new InstanceFence(), mock(OperatorNotice.class), PATIENT_TIMING);
            try {
                ((AdvisoryInstanceLock) first.bean(InstanceLock.class)).heartbeat();

                assertEquals(InstanceFence.State.FENCED, first.fence().state());
                assertThrows(DatabaseUnavailableException.class, () -> first.dsl().fetchCount(SETTINGS));
                assertThrows(DatabaseUnavailableException.class, () -> first.unitOfWork().read(() -> first.dsl().fetchCount(SETTINGS)));
            } finally {
                second.destroy();
            }
        }
    }

    @ExternalDialectTest
    void aKilledLockSessionThatNobodyTakesIsReacquiredOnTheNextHeartbeat(DialectTarget target) throws Exception {
        SqlDatabaseProperties database = TestDialects.freshDatabase(target);
        try (SqlTestDatabase first = SqlTestDatabase.start(target, dataDirectory, database, WriteAttemptHook.NONE);
             ExternalLockSessions sessions = ExternalLockSessions.open(database)) {
            sessions.killHolder();
            sessions.awaitFree();

            ((AdvisoryInstanceLock) first.bean(InstanceLock.class)).heartbeat();

            assertEquals(InstanceFence.State.SERVING, first.fence().state());
            assertTrue(sessions.isHeld());
            assertEquals(0, first.unitOfWork().read(() -> first.dsl().fetchCount(SETTINGS)));
            IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> acquireExternal(database, new InstanceFence(), mock(OperatorNotice.class), SHORT_TIMING));
            assertTrue(failure.getMessage().contains(EXTERNAL_HELD_REMEDY), failure.getMessage());
        }
    }

    private void assertOtherProcessesAreLockedOut() throws InterruptedException {
        try (ChildJvm probe = ChildJvm.start(InstanceHolderProcess.class, dataDirectory.toString())) {
            String outcome = probe.awaitLineStartingWith(InstanceHolderProcess.READY, InstanceHolderProcess.FAILED);
            assertTrue(outcome.startsWith(InstanceHolderProcess.FAILED), outcome);
            assertTrue(outcome.contains(OTHER_PROCESS_REMEDY), outcome);
        }
    }

    private Path databaseDirectory() {
        return dataDirectory.resolve("database");
    }

    private String marker() {
        return FileInstanceLock.markerName(FileInstanceLock.lockFile(databaseDirectory()));
    }

    private static AdvisoryInstanceLock acquireExternal(SqlDatabaseProperties database, InstanceFence fence, OperatorNotice notice,
                                                        AdvisoryInstanceLock.Timing timing) {
        ConnectionTracker tracker = new ConnectionTracker();
        return AdvisoryInstanceLock.acquire(database.dialect(), ExternalDatabaseConnections.lockConnections(database, tracker),
            fence, notice, releaseFor(tracker), timing);
    }

    private static LockRelease releaseFor(ConnectionTracker tracker) {
        return new LockRelease(tracker, ClassLoader.getPlatformClassLoader());
    }

    private static DataSource dataSourceOfMockConnections() throws SQLException {
        DataSource dataSource = mock(DataSource.class);
        when(dataSource.getConnection()).thenAnswer(invocation -> mock(Connection.class));
        return dataSource;
    }
}

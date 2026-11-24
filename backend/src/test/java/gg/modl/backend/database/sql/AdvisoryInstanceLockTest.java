package gg.modl.backend.database.sql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import gg.modl.backend.database.DatabaseDialect;
import gg.modl.backend.infrastructure.logging.OperatorNotice;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

class AdvisoryInstanceLockTest {
    private static final long FIRST_SESSION = 1;
    private static final Duration DESTROY_BOUND = Duration.ofMillis(500);
    private static final Duration RELEASE_TIMEOUT = Duration.ofSeconds(5);
    private static final AdvisoryInstanceLock.Timing TIMING = new AdvisoryInstanceLock.Timing(Duration.ofSeconds(2),
        Duration.ofMillis(50), Duration.ofSeconds(30), Duration.ofHours(1), true);

    private final FakeLockServer server = new FakeLockServer();
    private final InstanceFence fence = new InstanceFence();
    private final OperatorNotice notice = mock(OperatorNotice.class);
    private final ConnectionTracker tracker = new ConnectionTracker();

    @Test
    void aHeartbeatOnTheLiveOwnSessionKeepsServing() {
        AdvisoryInstanceLock lock = acquire();

        lock.heartbeat();

        assertEquals(InstanceFence.State.SERVING, fence.state());
        assertEquals(FIRST_SESSION, server.holder());
        verify(notice, never()).publish(anyString());
        lock.destroy();
    }

    @Test
    void aNetworkBlipWhileTheServerKeepsTheOldSessionEndsItAndTakesTheLockAgain() {
        AdvisoryInstanceLock lock = acquire();
        server.breakClientOf(FIRST_SESSION);

        lock.heartbeat();

        assertEquals(InstanceFence.State.SERVING, fence.state());
        assertTrue(server.holder() != null && server.holder() != FIRST_SESSION, String.valueOf(server.holder()));
        verify(notice, never()).publish(anyString());
        lock.destroy();
    }

    @Test
    void aLockTakenByAnotherSessionFencesTheInstanceWithOneNotice() {
        AdvisoryInstanceLock lock = acquire();
        server.breakClientOf(FIRST_SESSION);
        server.takeLockFor(FakeLockServer.OTHER_SESSION);

        lock.heartbeat();
        lock.heartbeat();

        assertEquals(InstanceFence.State.FENCED, fence.state());
        verify(notice).publish(AdvisoryInstanceLock.LOST_NOTICE);
        lock.destroy();
    }

    @Test
    void destroyReturnsWhileAHeartbeatIsStuckAndTheLockIsReleasedAfterIt() throws Exception {
        AdvisoryInstanceLock lock = acquire();
        FakeLockServer.BlockedCheck blocked = server.blockOwnershipChecksOf(FIRST_SESSION);
        CompletableFuture<Void> stuckBeat = CompletableFuture.runAsync(lock::heartbeat);
        blocked.entered().await();

        long started = System.nanoTime();
        lock.destroy();
        Duration destroy = Duration.ofNanos(System.nanoTime() - started);

        assertTrue(destroy.compareTo(DESTROY_BOUND) < 0, destroy.toString());
        assertEquals(FIRST_SESSION, server.holder());
        blocked.release().countDown();
        stuckBeat.join();
        awaitReleased();
    }

    @Test
    void aRetainedLockKeepsBeatingAndIsReleasedOnceTheLastConnectionCloses() throws SQLException {
        AdvisoryInstanceLock lock = acquire();
        Connection open = tracker.track(server.connections(), ConnectionRole.POOL).getConnection();
        lock.destroy();

        lock.heartbeat();
        assertEquals(FIRST_SESSION, server.holder());

        open.close();
        lock.heartbeat();

        assertNull(server.holder());
        verify(notice, never()).publish(anyString());
    }

    @Test
    void aRetainedLockThatIsLostPublishesTheRetainedNotice() throws SQLException {
        AdvisoryInstanceLock lock = acquire();
        Connection open = tracker.track(server.connections(), ConnectionRole.POOL).getConnection();
        lock.destroy();
        server.breakClientOf(FIRST_SESSION);
        server.takeLockFor(FakeLockServer.OTHER_SESSION);

        lock.heartbeat();

        verify(notice).publish(AdvisoryInstanceLock.RETAINED_LOST_NOTICE);
        verify(notice, never()).publish(AdvisoryInstanceLock.LOST_NOTICE);
        open.close();
    }

    private AdvisoryInstanceLock acquire() {
        DataSource connections = server.connections();
        return AdvisoryInstanceLock.acquire(DatabaseDialect.MYSQL, connections, fence, notice,
            new LockRelease(tracker, ClassLoader.getPlatformClassLoader()), TIMING);
    }

    private void awaitReleased() throws InterruptedException {
        long deadline = System.nanoTime() + RELEASE_TIMEOUT.toNanos();
        while (server.holder() != null) {
            if (System.nanoTime() >= deadline) {
                throw new IllegalStateException("The lock was not released within " + RELEASE_TIMEOUT);
            }
            Thread.sleep(10);
        }
    }
}

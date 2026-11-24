package gg.modl.backend.database.sql;

import gg.modl.backend.database.DatabaseDialect;
import gg.modl.backend.infrastructure.logging.OperatorNotice;
import gg.modl.backend.infrastructure.scheduling.RepeatingTasks;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;

@Slf4j
final class AdvisoryInstanceLock implements InstanceLock {
    record Timing(Duration acquireBudget, Duration acquireRetry, Duration unreachableBudget, Duration heartbeatInterval,
                  boolean announcesWait) {
        static final Timing DEFAULT = new Timing(Duration.ofSeconds(150), Duration.ofSeconds(5), Duration.ofSeconds(30),
            Duration.ofSeconds(10), true);
    }

    static final String LOST_NOTICE = "modl lost its database lock and stopped serving to protect your data. Restart the server.";
    static final String RETAINED_LOST_NOTICE = "The stopped modl server still has open database connections and lost its "
        + "database lock. Restart the server before another modl server uses this database.";

    private enum Mode {
        SERVING,
        RETAINED,
        ENDED
    }

    private enum Attempt {
        ACQUIRED,
        HELD_ELSEWHERE,
        RETRY
    }

    private final DatabaseDialect dialect;
    private final DataSource lockConnections;
    private final InstanceFence fence;
    private final OperatorNotice notice;
    private final LockRelease release;
    private final Timing timing;
    private final ScheduledExecutorService heartbeats;
    private final AtomicBoolean destroyed = new AtomicBoolean();
    private final AtomicReference<Mode> mode = new AtomicReference<>(Mode.SERVING);
    @Nullable
    private AdvisoryLockStatements statements;
    @Nullable
    private LockSession session;
    @Nullable
    private LockHolder ownSession;
    private long unreachableSinceNanos;

    private AdvisoryInstanceLock(DatabaseDialect dialect, DataSource lockConnections, InstanceFence fence, OperatorNotice notice,
                                 LockRelease release, Timing timing) {
        this.dialect = dialect;
        this.lockConnections = lockConnections;
        this.fence = fence;
        this.notice = notice;
        this.release = release;
        this.timing = timing;
        this.heartbeats = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "modl-db-lock-heartbeat");
            thread.setDaemon(true);
            return thread;
        });
    }

    static AdvisoryInstanceLock acquire(DatabaseDialect dialect, DataSource lockConnections, InstanceFence fence,
                                        OperatorNotice notice, LockRelease release, Timing timing) {
        AdvisoryInstanceLock lock = new AdvisoryInstanceLock(dialect, lockConnections, fence, notice, release, timing);
        try {
            lock.acquireOrFail();
        } catch (RuntimeException e) {
            lock.heartbeats.shutdownNow();
            throw e;
        }
        long interval = timing.heartbeatInterval().toNanos();
        RepeatingTasks.withFixedDelay(lock.heartbeats, lock::beatSafely, interval, interval, TimeUnit.NANOSECONDS);
        return lock;
    }

    @Override
    public void destroy() {
        if (!destroyed.compareAndSet(false, true)) {
            return;
        }
        if (!release.connectionsClosed()) {
            log.warn("{} database connections are still open, so the database lock stays held until they close",
                release.openConnections());
            if (!mode.compareAndSet(Mode.SERVING, Mode.RETAINED)) {
                heartbeats.shutdown();
            }
            return;
        }
        try {
            heartbeats.execute(this::endSession);
            heartbeats.shutdown();
        } catch (RejectedExecutionException e) {
            log.debug("The database lock heartbeat had already stopped", e);
        }
        release.releaseDrivers();
    }

    void heartbeat() {
        try {
            heartbeats.submit(this::beat).get();
        } catch (ExecutionException e) {
            throw new IllegalStateException("The database lock heartbeat failed", e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for the database lock heartbeat", e);
        }
    }

    private void beatSafely() {
        try {
            beat();
        } catch (RuntimeException e) {
            log.warn("The database lock heartbeat failed", e);
        }
    }

    private void beat() {
        switch (mode.get()) {
            case ENDED -> {
            }
            case RETAINED -> {
                if (release.connectionsClosed()) {
                    endSession();
                    release.releaseDrivers();
                } else {
                    keepLock();
                }
            }
            case SERVING -> keepLock();
        }
    }

    private void keepLock() {
        if (session != null && session.owns()) {
            unreachableSinceNanos = 0;
            return;
        }
        if (unreachableSinceNanos == 0) {
            unreachableSinceNanos = System.nanoTime();
        }
        closeSession();
        Attempt attempt = attempt();
        if (attempt == Attempt.ACQUIRED) {
            unreachableSinceNanos = 0;
            log.warn("The database lock connection was lost and the lock was taken again on a fresh connection");
            return;
        }
        boolean unreachableTooLong = System.nanoTime() - unreachableSinceNanos >= timing.unreachableBudget().toNanos();
        if (attempt == Attempt.HELD_ELSEWHERE || unreachableTooLong) {
            lose();
        }
    }

    private void lose() {
        Mode lostIn = mode.getAndSet(Mode.ENDED);
        closeSession();
        if (lostIn == Mode.RETAINED) {
            notice.publish(RETAINED_LOST_NOTICE);
            heartbeats.shutdown();
        } else if (fence.fence()) {
            notice.publish(LOST_NOTICE);
        }
    }

    private void endSession() {
        mode.set(Mode.ENDED);
        try {
            unlock();
        } finally {
            closeSession();
            heartbeats.shutdown();
        }
    }

    private void acquireOrFail() {
        long deadline = System.nanoTime() + timing.acquireBudget().toNanos();
        boolean announced = false;
        while (attempt() != Attempt.ACQUIRED) {
            if (System.nanoTime() >= deadline) {
                throw new InstanceLockHeldException("Another modl server is using this database. Stop it, or give each "
                    + "server its own database.");
            }
            if (!announced && timing.announcesWait()) {
                notice.publish("Another modl server is using this database; waiting up to {} seconds for it to stop.",
                    timing.acquireBudget().toSeconds());
                announced = true;
            }
            pause(timing.acquireRetry());
        }
    }

    private Attempt attempt() {
        LockSession fresh;
        try {
            fresh = openSession();
        } catch (SQLException e) {
            log.debug("The database lock connection could not be opened", e);
            return Attempt.RETRY;
        }
        try {
            Attempt attempt = lockOn(fresh);
            if (attempt == Attempt.ACQUIRED) {
                session = fresh;
                ownSession = fresh.self();
            } else {
                fresh.close();
            }
            return attempt;
        } catch (SQLException e) {
            log.debug("The database lock could not be taken", e);
            fresh.close();
            return Attempt.RETRY;
        }
    }

    private Attempt lockOn(LockSession fresh) throws SQLException {
        if (fresh.tryLock()) {
            return Attempt.ACQUIRED;
        }
        Optional<LockHolder> holder = fresh.holder();
        if (holder.isEmpty()) {
            return Attempt.RETRY;
        }
        if (!holder.get().equals(ownSession)) {
            return Attempt.HELD_ELSEWHERE;
        }
        log.warn("The database lock is still held by this server's previous lock session; ending that session");
        fresh.terminate(holder.get());
        return fresh.tryLock() ? Attempt.ACQUIRED : Attempt.RETRY;
    }

    private LockSession openSession() throws SQLException {
        Connection connection = lockConnections.getConnection();
        try {
            AdvisoryLockStatements lockStatements = statements == null ? AdvisoryLockStatements.of(dialect, connection) : statements;
            statements = lockStatements;
            lockStatements.prepareSession(connection);
            return new LockSession(connection, lockStatements, lockStatements.self(connection));
        } catch (SQLException | RuntimeException e) {
            LockSession.closeQuietly(connection);
            throw e;
        }
    }

    private void unlock() {
        if (session == null) {
            return;
        }
        try {
            session.unlock();
        } catch (SQLException e) {
            log.warn("Could not release the database lock; it ends with the lock connection", e);
        }
    }

    private void closeSession() {
        LockSession current = session;
        session = null;
        if (current != null) {
            current.close();
        }
    }

    private static void pause(Duration duration) {
        try {
            Thread.sleep(duration);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for the database lock", e);
        }
    }
}

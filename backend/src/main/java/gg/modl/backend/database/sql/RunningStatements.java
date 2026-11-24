package gg.modl.backend.database.sql;

import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;

@Slf4j
public final class RunningStatements implements AutoCloseable {
    public enum CancelReason {
        DEADLINE,
        SHUTDOWN
    }

    private static final int CANCEL_THREADS = 4;
    private static final Duration IDLE_CANCEL_THREAD_LIFETIME = Duration.ofSeconds(5);

    private final Map<Statement, Execution> running = new ConcurrentHashMap<>();
    private final ThreadPoolExecutor cancellations = cancelPool();

    public void register(Statement statement) {
        running.put(statement, new Execution(statement));
    }

    public void deregister(Statement statement) {
        Execution execution = running.remove(statement);
        if (execution != null) {
            execution.end();
        }
    }

    public int size() {
        return running.size();
    }

    public Optional<CancelReason> cancellationOf(Statement statement) {
        return Optional.ofNullable(running.get(statement)).flatMap(Execution::reason);
    }

    public void cancel(Statement statement, CancelReason reason) {
        Execution execution = running.get(statement);
        if (execution != null) {
            cancelInBackground(execution, reason);
        }
    }

    public int cancelAll(Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        List<Future<Boolean>> pending = List.copyOf(running.values()).stream()
            .map(execution -> submit(execution, CancelReason.SHUTDOWN))
            .toList();
        int cancelled = 0;
        for (Future<Boolean> cancellation : pending) {
            if (awaitCancellation(cancellation, deadline)) {
                cancelled++;
            }
        }
        return cancelled;
    }

    @Override
    public void close() {
        cancellations.shutdownNow();
    }

    private void cancelInBackground(Execution execution, CancelReason reason) {
        try {
            cancellations.execute(() -> execution.cancel(reason));
        } catch (RejectedExecutionException e) {
            log.debug("A running statement was not cancelled because the database is closing", e);
        }
    }

    private Future<Boolean> submit(Execution execution, CancelReason reason) {
        try {
            return cancellations.submit(() -> execution.cancel(reason));
        } catch (RejectedExecutionException e) {
            log.debug("A running statement was not cancelled because the database is closing", e);
            return CompletableFuture.completedFuture(false);
        }
    }

    private static boolean awaitCancellation(Future<Boolean> cancellation, long deadlineNanos) {
        try {
            return cancellation.get(Math.max(0, deadlineNanos - System.nanoTime()), TimeUnit.NANOSECONDS);
        } catch (TimeoutException e) {
            log.debug("A statement cancel did not finish in time", e);
            return false;
        } catch (ExecutionException e) {
            log.debug("A statement cancel failed", e.getCause());
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static ThreadPoolExecutor cancelPool() {
        AtomicInteger counter = new AtomicInteger();
        ThreadPoolExecutor pool = new ThreadPoolExecutor(CANCEL_THREADS, CANCEL_THREADS, IDLE_CANCEL_THREAD_LIFETIME.toMillis(),
            TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>(), task -> {
                Thread thread = new Thread(task, "modl-db-cancel-" + counter.incrementAndGet());
                thread.setDaemon(true);
                return thread;
            });
        pool.allowCoreThreadTimeOut(true);
        return pool;
    }

    private static final class Execution {
        private final Statement statement;
        @Nullable
        private CancelReason reason;
        private boolean ended;

        private Execution(Statement statement) {
            this.statement = statement;
        }

        private synchronized Optional<CancelReason> reason() {
            return Optional.ofNullable(reason);
        }

        private synchronized boolean cancel(CancelReason requested) {
            if (ended) {
                return false;
            }
            if (reason == null) {
                reason = requested;
            }
            try {
                statement.cancel();
                return true;
            } catch (SQLException | RuntimeException e) {
                log.debug("Could not cancel a running statement", e);
                return false;
            }
        }

        private synchronized void end() {
            ended = true;
        }
    }
}

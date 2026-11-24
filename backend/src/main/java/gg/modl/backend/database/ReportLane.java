package gg.modl.backend.database;

import java.time.Duration;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

public final class ReportLane {
    public static final Duration DEFAULT_DEADLINE = Duration.ofSeconds(30);
    public static final Duration SEARCH_DEADLINE = Duration.ofSeconds(5);
    public static final Duration ACQUIRE_TIMEOUT = Duration.ofSeconds(2);

    private final Semaphore permits;
    private final int capacity;
    private final Duration deadline;

    public ReportLane(int permits, Duration deadline) {
        this.permits = new Semaphore(permits, true);
        this.capacity = permits;
        this.deadline = deadline;
    }

    public int permits() {
        return capacity;
    }

    public Duration deadline() {
        return deadline;
    }

    public <T> T run(Supplier<T> report) {
        acquire();
        try {
            return report.get();
        } finally {
            permits.release();
        }
    }

    private void acquire() {
        try {
            if (!permits.tryAcquire(ACQUIRE_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
                throw new DatabaseBusyException("Too many reports are running; try again shortly",
                    DatabaseBusyException.LANE_RETRY_AFTER);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DatabaseBusyException("Interrupted while waiting for the report lane",
                DatabaseBusyException.LANE_RETRY_AFTER, e);
        }
    }
}

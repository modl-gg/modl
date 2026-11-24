package gg.modl.backend.database.sql;

import gg.modl.backend.database.InFlightUnits;
import java.time.Duration;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.SmartLifecycle;

@Slf4j
public final class DatabaseLifecycle implements SmartLifecycle {
    public static final int PHASE = SmartLifecycle.DEFAULT_PHASE - 4096;
    public static final Duration DRAIN_BUDGET = Duration.ofSeconds(10);
    public static final Duration CANCEL_MARGIN = Duration.ofSeconds(2);

    private static final Duration POLL_INTERVAL = Duration.ofMillis(20);

    private final InstanceFence fence;
    private final InFlightUnits units;
    private final DatabaseWorkExecutor work;
    private final RunningStatements statements;
    private final ConnectionPools pools;
    private final Duration drainBudget;
    private volatile boolean running;

    DatabaseLifecycle(InstanceFence fence, InFlightUnits units, DatabaseWorkExecutor work,
                             RunningStatements statements, ConnectionPools pools, Duration drainBudget) {
        this.fence = fence;
        this.units = units;
        this.work = work;
        this.statements = statements;
        this.pools = pools;
        this.drainBudget = drainBudget;
    }

    @Override
    public void start() {
        running = true;
    }

    @Override
    public void stop() {
        try {
            drain();
        } finally {
            running = false;
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public int getPhase() {
        return PHASE;
    }

    public void drain() {
        long deadline = System.nanoTime() + drainBudget.toNanos();
        fence.beginDraining();
        work.stopAccepting();
        if (!awaitIdle(deadline - CANCEL_MARGIN.toNanos())) {
            int cancelled = statements.cancelAll(Duration.ofNanos(Math.max(0, deadline - System.nanoTime())));
            log.warn("Cancelled {} database statements that were still running at shutdown", cancelled);
            awaitIdle(deadline);
        }
        if (!pools.closeIfIdle()) {
            log.warn("A database connection is still in use after shutdown; its pool stays open");
        }
    }

    private boolean awaitIdle(long deadlineNanos) {
        while (!isIdle()) {
            if (System.nanoTime() >= deadlineNanos) {
                return false;
            }
            try {
                Thread.sleep(POLL_INTERVAL);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return isIdle();
            }
        }
        return true;
    }

    private boolean isIdle() {
        return units.inFlight() == 0 && work.inFlight() == 0;
    }
}

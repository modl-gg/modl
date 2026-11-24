package gg.modl.backend.database.sql;

import gg.modl.backend.database.DatabaseBusyException;
import gg.modl.backend.database.DatabaseUnavailableException;
import gg.modl.backend.database.DatabaseUnavailableException.Reason;
import gg.modl.backend.database.sql.RunningStatements.CancelReason;
import gg.modl.backend.database.UnitFrame;
import java.sql.Statement;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import org.jooq.ExecuteContext;
import org.jooq.ExecuteListener;

public final class StatementDeadline implements ExecuteListener, AutoCloseable {
    private static final String STATEMENT_KEY = "modl.statement";
    private static final String TIMER_KEY = "modl.statement.deadline";

    private final RunningStatements running;
    private final ScheduledExecutorService timer;

    public StatementDeadline(RunningStatements running) {
        this.running = running;
        this.timer = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "modl-db-deadline");
            thread.setDaemon(true);
            return thread;
        });
    }

    @Override
    public void executeStart(ExecuteContext context) {
        Statement statement = context.statement();
        if (statement == null) {
            return;
        }
        running.register(statement);
        context.data(STATEMENT_KEY, statement);
        UnitFrame.current().filter(UnitFrame::hasDeadline).ifPresent(frame -> scheduleCancel(context, statement, frame));
    }

    @Override
    public void end(ExecuteContext context) {
        release(context);
    }

    @Override
    public void exception(ExecuteContext context) {
        RuntimeException failure = context.exception();
        if (failure != null && context.data(STATEMENT_KEY) instanceof Statement statement) {
            running.cancellationOf(statement).ifPresent(reason -> context.exception(cancelled(reason, failure)));
        }
        release(context);
    }

    @Override
    public void close() {
        timer.shutdownNow();
    }

    private void scheduleCancel(ExecuteContext context, Statement statement, UnitFrame frame) {
        long remaining = frame.deadlineNanos() - System.nanoTime();
        if (remaining <= 0) {
            release(context);
            throw new DatabaseBusyException("The report ran out of time", DatabaseBusyException.LANE_RETRY_AFTER);
        }
        context.data(TIMER_KEY, timer.schedule(() -> running.cancel(statement, CancelReason.DEADLINE),
            remaining, TimeUnit.NANOSECONDS));
    }

    private static RuntimeException cancelled(CancelReason reason, RuntimeException cause) {
        return switch (reason) {
            case DEADLINE -> new DatabaseBusyException("The report ran out of time and was cancelled",
                DatabaseBusyException.LANE_RETRY_AFTER, cause);
            case SHUTDOWN -> new DatabaseUnavailableException(Reason.SHUTTING_DOWN, cause);
        };
    }

    private void release(ExecuteContext context) {
        if (context.data(TIMER_KEY) instanceof ScheduledFuture<?> cancellation) {
            cancellation.cancel(false);
            context.data().remove(TIMER_KEY);
        }
        if (context.data(STATEMENT_KEY) instanceof Statement statement) {
            running.deregister(statement);
            context.data().remove(STATEMENT_KEY);
        }
    }
}

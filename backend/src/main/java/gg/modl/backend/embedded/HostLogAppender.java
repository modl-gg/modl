package gg.modl.backend.embedded;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import gg.modl.backend.infrastructure.logging.OperatorNotice;
import gg.modl.backend.infrastructure.scheduling.RepeatingTasks;
import org.jspecify.annotations.Nullable;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

final class HostLogAppender extends AppenderBase<ILoggingEvent> {
    static final String SUMMARY_THREAD = "modl-log-summary";

    private final HostLog hostLog;
    private final String logFile;
    private final Duration summaryInterval;
    private final AtomicLong unreportedProblems = new AtomicLong();
    private @Nullable ScheduledExecutorService summaryScheduler;

    HostLogAppender(HostLog hostLog, String logFile, Duration summaryInterval) {
        this.hostLog = hostLog;
        this.logFile = logFile;
        this.summaryInterval = summaryInterval;
    }

    @Override
    public void start() {
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, SUMMARY_THREAD);
            thread.setDaemon(true);
            return thread;
        });
        summaryScheduler = scheduler;
        long intervalMillis = summaryInterval.toMillis();
        RepeatingTasks.atFixedRate(scheduler, this::reportProblems, intervalMillis, intervalMillis, TimeUnit.MILLISECONDS);
        super.start();
    }

    @Override
    public void stop() {
        super.stop();
        ScheduledExecutorService scheduler = summaryScheduler;
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
    }

    @Override
    protected void append(ILoggingEvent event) {
        if (OperatorNotice.LOGGER_NAME.equals(event.getLoggerName())) {
            hostLog.notice(event.getFormattedMessage());
        } else if (event.getLevel().isGreaterOrEqual(Level.WARN)) {
            unreportedProblems.incrementAndGet();
        }
    }

    private void reportProblems() {
        long problems = unreportedProblems.getAndSet(0);
        if (problems > 0) {
            hostLog.warn(problems + " warnings/errors, see " + logFile);
        }
    }
}

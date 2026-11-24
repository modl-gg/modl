package gg.modl.backend.infrastructure.scheduling;

import lombok.extern.slf4j.Slf4j;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

@Slf4j
public final class RepeatingTasks {

    private RepeatingTasks() {
    }

    public static Runnable withFixedDelay(ScheduledExecutorService executor, Runnable task, long initialDelay, long delay,
                                          TimeUnit unit) {
        return cancelling(executor.scheduleWithFixedDelay(guarded(task), initialDelay, delay, unit));
    }

    public static Runnable atFixedRate(ScheduledExecutorService executor, Runnable task, long initialDelay, long period,
                                       TimeUnit unit) {
        return cancelling(executor.scheduleAtFixedRate(guarded(task), initialDelay, period, unit));
    }

    private static Runnable guarded(Runnable task) {
        return () -> {
            try {
                task.run();
            } catch (RuntimeException e) {
                log.warn("A repeating task failed and runs again at its next turn", e);
            }
        };
    }

    private static Runnable cancelling(ScheduledFuture<?> scheduled) {
        return () -> scheduled.cancel(false);
    }
}

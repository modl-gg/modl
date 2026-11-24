package gg.modl.minecraft.core.util;

import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

public final class Scheduling {
    private Scheduling() {
    }

    public static Runnable withFixedDelay(ScheduledExecutorService executor, Runnable task, long initialDelay, long delay,
                                          TimeUnit unit, PluginLogger logger) {
        return cancelling(executor.scheduleWithFixedDelay(guarded(task, logger), initialDelay, delay, unit));
    }

    public static Runnable atFixedRate(ScheduledExecutorService executor, Runnable task, long initialDelay, long period,
                                       TimeUnit unit, PluginLogger logger) {
        return cancelling(executor.scheduleAtFixedRate(guarded(task, logger), initialDelay, period, unit));
    }

    public static Runnable after(ScheduledExecutorService executor, Runnable task, long delay, TimeUnit unit,
                                 PluginLogger logger) {
        return cancelling(executor.schedule(guarded(task, logger), delay, unit));
    }

    private static Runnable guarded(Runnable task, PluginLogger logger) {
        return () -> {
            try {
                task.run();
            } catch (RuntimeException failure) {
                logger.warning("A scheduled modl task failed", failure);
            }
        };
    }

    private static Runnable cancelling(ScheduledFuture<?> scheduled) {
        return () -> scheduled.cancel(false);
    }
}

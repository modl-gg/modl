package gg.modl.minecraft.core.util;

import gg.modl.minecraft.core.support.RecordingPluginLogger;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SchedulingTest {
    private final RecordingPluginLogger logger = new RecordingPluginLogger();

    @Test
    void aRepeatingTaskThatThrowsKeepsRunningAndIsLogged() throws InterruptedException {
        ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
        try {
            AtomicInteger runs = new AtomicInteger();
            CountDownLatch thirdRun = new CountDownLatch(3);
            Scheduling.withFixedDelay(executor, () -> {
                runs.incrementAndGet();
                thirdRun.countDown();
                throw new IllegalStateException("tick failed");
            }, 0, 1, TimeUnit.MILLISECONDS, logger);

            assertTrue(thirdRun.await(5, TimeUnit.SECONDS));
            assertTrue(runs.get() >= 3);
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
        assertTrue(logger.warnings().size() >= 3);
    }

    @Test
    void theReturnedHandleCancelsTheTask() throws InterruptedException {
        ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
        try {
            AtomicInteger runs = new AtomicInteger();
            Runnable cancel = Scheduling.after(executor, runs::incrementAndGet, 1, TimeUnit.HOURS, logger);

            cancel.run();
            executor.shutdown();

            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
            assertEquals(0, runs.get());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void aRejectedScheduleStillReachesTheCaller() {
        ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
        executor.shutdownNow();

        assertThrows(RejectedExecutionException.class,
                () -> Scheduling.after(executor, () -> { }, 1, TimeUnit.SECONDS, logger));
    }
}

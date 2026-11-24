package gg.modl.backend.infrastructure.scheduling;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class RepeatingTasksTest {

    @Test
    void aTaskThatThrowsRunsAgainAtItsNextTurn() throws InterruptedException {
        ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
        try {
            CountDownLatch thirdRun = new CountDownLatch(3);
            RepeatingTasks.withFixedDelay(executor, () -> {
                thirdRun.countDown();
                throw new IllegalStateException("tick failed");
            }, 0, 1, TimeUnit.MILLISECONDS);

            assertThat(thirdRun.await(5, TimeUnit.SECONDS)).isTrue();
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void theReturnedHandleStopsTheTask() throws InterruptedException {
        ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
        CountDownLatch ran = new CountDownLatch(1);
        Runnable cancel = RepeatingTasks.atFixedRate(executor, ran::countDown, 1, 1, TimeUnit.HOURS);

        cancel.run();
        executor.shutdown();

        assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        assertThat(ran.getCount()).isEqualTo(1);
    }

    @Test
    void aRejectedScheduleReachesTheCaller() {
        ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
        executor.shutdownNow();

        assertThatThrownBy(() -> RepeatingTasks.withFixedDelay(executor, () -> { }, 1, 1, TimeUnit.SECONDS))
            .isInstanceOf(RejectedExecutionException.class);
    }
}

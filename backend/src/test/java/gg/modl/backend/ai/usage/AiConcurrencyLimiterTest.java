package gg.modl.backend.ai.usage;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class AiConcurrencyLimiterTest {
    private final AiConcurrencyLimiter limiter = new AiConcurrencyLimiter();

    @Test
    void callsBeyondTheLimitWaitForASlot() throws Exception {
        AiConcurrencyLimiter.Permit first = limiter.acquire(1).orElseThrow();
        CompletableFuture<Optional<AiConcurrencyLimiter.Permit>> waiting =
            CompletableFuture.supplyAsync(() -> limiter.acquire(1, Duration.ofSeconds(10)));
        Thread.sleep(100);
        assertThat(waiting).isNotDone();

        first.close();

        assertThat(waiting.get(5, TimeUnit.SECONDS)).isPresent();
    }

    @Test
    void aWaitThatTimesOutIsBusy() {
        AiConcurrencyLimiter.Permit held = limiter.acquire(1).orElseThrow();

        assertThat(limiter.acquire(1, Duration.ofMillis(50))).isEmpty();
        held.close();
    }

    @Test
    void raisingTheLimitAdmitsMoreCallsAndClosingTwiceReleasesOnce() {
        AiConcurrencyLimiter.Permit first = limiter.acquire(1).orElseThrow();
        AiConcurrencyLimiter.Permit second = limiter.acquire(2, Duration.ZERO).orElseThrow();
        assertThat(limiter.active()).isEqualTo(2);

        first.close();
        first.close();
        assertThat(limiter.active()).isEqualTo(1);
        second.close();
    }

    @Test
    void theQueueIsCapped() throws Exception {
        AiConcurrencyLimiter.Permit held = limiter.acquire(1).orElseThrow();
        List<CompletableFuture<Optional<AiConcurrencyLimiter.Permit>>> queued = new ArrayList<>();
        for (int caller = 0; caller < AiConcurrencyLimiter.MAX_WAITING; caller++) {
            queued.add(CompletableFuture.supplyAsync(() -> limiter.acquire(1, Duration.ofSeconds(5)),
                runnable -> Thread.ofVirtual().start(runnable)));
        }
        Thread.sleep(300);

        assertThat(limiter.acquire(1, Duration.ofSeconds(5))).isEmpty();

        held.close();
        for (CompletableFuture<Optional<AiConcurrencyLimiter.Permit>> call : queued) {
            call.get(10, TimeUnit.SECONDS).ifPresent(AiConcurrencyLimiter.Permit::close);
        }
    }
}

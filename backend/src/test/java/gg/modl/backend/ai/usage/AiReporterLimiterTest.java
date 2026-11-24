package gg.modl.backend.ai.usage;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class AiReporterLimiterTest {
    private static final Instant NOW = Instant.parse("2026-11-22T12:00:00Z");
    private final AiReporterLimiter limiter = new AiReporterLimiter();

    @Test
    void eachReporterGetsARollingHourlyAllowance() {
        for (int report = 0; report < 2; report++) {
            assertThat(limiter.tryAcquire("11111111-2222-3333-4444-555555555555", 2, NOW)).isTrue();
        }

        assertThat(limiter.tryAcquire("11111111222233334444555555555555", 2, NOW.plusSeconds(10))).isFalse();
        assertThat(limiter.tryAcquire("99999999-2222-3333-4444-555555555555", 2, NOW)).isTrue();
        assertThat(limiter.tryAcquire("11111111-2222-3333-4444-555555555555", 2, NOW.plus(Duration.ofMinutes(61)))).isTrue();
    }

    @Test
    void unknownReportersShareOneBucket() {
        assertThat(limiter.tryAcquire(null, 1, NOW)).isTrue();
        assertThat(limiter.tryAcquire(" ", 1, NOW)).isFalse();
    }
}

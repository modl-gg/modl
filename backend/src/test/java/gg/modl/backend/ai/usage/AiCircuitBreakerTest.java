package gg.modl.backend.ai.usage;

import static org.assertj.core.api.Assertions.assertThat;

import gg.modl.backend.ai.model.AiFailureKind;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class AiCircuitBreakerTest {
    private static final Instant NOW = Instant.parse("2026-11-22T12:00:00Z");
    private final AiCircuitBreaker breaker = new AiCircuitBreaker();

    @Test
    void anAuthenticationFailureOpensTheBreakerAtOnce() {
        assertThat(breaker.recordFailure(AiFailureKind.AUTH, NOW)).isTrue();

        assertThat(breaker.allows(NOW.plus(AiCircuitBreaker.HARD_PAUSE).minusSeconds(1))).isFalse();
        assertThat(breaker.pausedUntil(NOW)).isEqualTo(NOW.plus(AiCircuitBreaker.HARD_PAUSE));
    }

    @Test
    void fiveCountedFailuresInARowPauseBriefly() {
        for (int failure = 1; failure < AiCircuitBreaker.SOFT_THRESHOLD; failure++) {
            assertThat(breaker.recordFailure(AiFailureKind.CONNECT, NOW)).isFalse();
        }

        assertThat(breaker.recordFailure(AiFailureKind.CONNECT, NOW)).isTrue();
        assertThat(breaker.pausedUntil(NOW)).isEqualTo(NOW.plus(AiCircuitBreaker.SOFT_PAUSE));
    }

    @Test
    void contentFailuresAndLocalSkipsNeverMoveTheBreaker() {
        for (int failure = 0; failure < 10; failure++) {
            breaker.recordFailure(AiFailureKind.INVALID_RESPONSE, NOW);
            breaker.recordFailure(AiFailureKind.BUSY, NOW);
            breaker.recordFailure(AiFailureKind.REPORTER_LIMIT, NOW);
        }

        assertThat(breaker.allows(NOW)).isTrue();
    }

    @Test
    void afterThePauseExactlyOneHalfOpenAttemptIsAdmitted() {
        breaker.recordFailure(AiFailureKind.QUOTA, NOW);
        Instant later = NOW.plus(AiCircuitBreaker.HARD_PAUSE).plusSeconds(1);

        assertThat(breaker.allows(later)).isTrue();
        assertThat(breaker.allows(later)).isFalse();

        breaker.recordSuccess();
        assertThat(breaker.allows(later)).isTrue();
        assertThat(breaker.pausedUntil(later)).isNull();
    }

    @Test
    void aFailedHalfOpenAttemptPausesAgain() {
        breaker.recordFailure(AiFailureKind.AUTH, NOW);
        Instant later = NOW.plus(AiCircuitBreaker.HARD_PAUSE).plusSeconds(1);
        breaker.allows(later);

        breaker.recordFailure(AiFailureKind.TIMEOUT, later);

        assertThat(breaker.allows(later.plusSeconds(1))).isFalse();
    }

    @Test
    void resetClosesTheBreaker() {
        breaker.recordFailure(AiFailureKind.AUTH, NOW);

        breaker.reset();

        assertThat(breaker.allows(NOW)).isTrue();
    }
}

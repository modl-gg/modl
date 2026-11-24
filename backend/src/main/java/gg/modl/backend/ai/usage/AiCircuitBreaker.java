package gg.modl.backend.ai.usage;

import gg.modl.backend.ai.model.AiFailureKind;
import org.jspecify.annotations.Nullable;
import java.time.Duration;
import java.time.Instant;

public final class AiCircuitBreaker {
    static final Duration HARD_PAUSE = Duration.ofMinutes(15);
    static final Duration SOFT_PAUSE = Duration.ofMinutes(5);
    static final int SOFT_THRESHOLD = 5;
    static final Duration PROBE_LIFETIME = Duration.ofMinutes(5);

    @Nullable
    private Instant pausedUntil;
    @Nullable
    private Instant probeStartedAt;
    private int consecutiveFailures;

    public synchronized boolean allows(Instant now) {
        if (pausedUntil == null) {
            return true;
        }
        if (now.isBefore(pausedUntil) || (probeStartedAt != null && now.isBefore(probeStartedAt.plus(PROBE_LIFETIME)))) {
            return false;
        }
        probeStartedAt = now;
        return true;
    }

    public synchronized boolean recordFailure(AiFailureKind kind, Instant now) {
        boolean wasPaused = pausedUntil != null && now.isBefore(pausedUntil);
        boolean halfOpenAttempt = probeStartedAt != null;
        probeStartedAt = null;
        switch (kind.breakerEffect()) {
            case OPEN_NOW -> pause(now.plus(HARD_PAUSE));
            case COUNT -> {
                consecutiveFailures++;
                if (consecutiveFailures >= SOFT_THRESHOLD || halfOpenAttempt) {
                    pause(now.plus(SOFT_PAUSE));
                }
            }
            case NONE -> {
                if (halfOpenAttempt) {
                    pausedUntil = null;
                }
            }
            case SKIP -> {
            }
        }
        return !wasPaused && pausedUntil != null && now.isBefore(pausedUntil);
    }

    public synchronized void recordSuccess() {
        reset();
    }

    public synchronized void reset() {
        pausedUntil = null;
        consecutiveFailures = 0;
        probeStartedAt = null;
    }

    @Nullable
    public synchronized Instant pausedUntil(Instant now) {
        return pausedUntil != null && now.isBefore(pausedUntil) ? pausedUntil : null;
    }

    private void pause(Instant until) {
        pausedUntil = until;
        consecutiveFailures = 0;
    }
}

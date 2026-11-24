package gg.modl.backend.update.check;

import org.jspecify.annotations.Nullable;
import java.time.Duration;
import java.time.Instant;
import java.util.random.RandomGenerator;

public final class CheckSchedule {
    static final Duration FIRST_CHECK_MIN = Duration.ofMinutes(2);
    static final Duration FIRST_CHECK_MAX = Duration.ofMinutes(10);
    static final Duration INTERVAL = Duration.ofHours(12);
    static final Duration INTERVAL_JITTER = Duration.ofHours(1);
    static final Duration LONGEST_BACKOFF = Duration.ofHours(24);
    static final Duration FURTHEST_AHEAD = Duration.ofHours(25);
    static final double BACKOFF_JITTER = 0.10;

    private final RandomGenerator random;

    public CheckSchedule(RandomGenerator random) {
        this.random = random;
    }

    public Instant firstCheck(Instant ready, @Nullable Instant stored) {
        Instant earliest = ready.plus(between(FIRST_CHECK_MIN, FIRST_CHECK_MAX));
        if (stored == null || stored.isBefore(earliest)) {
            return earliest;
        }
        return clamp(stored, ready);
    }

    public Instant afterSuccess(Instant now) {
        return now.plus(INTERVAL.minus(INTERVAL_JITTER).plus(between(Duration.ZERO, INTERVAL_JITTER.multipliedBy(2))));
    }

    public Instant afterFailure(Instant now, int failures, @Nullable Duration retryAfter) {
        int exponent = Math.max(0, Math.min(failures, 6) - 1);
        Duration base = Duration.ofHours(1L << exponent);
        if (base.compareTo(LONGEST_BACKOFF) > 0) {
            base = LONGEST_BACKOFF;
        }
        long jitterMillis = (long) (base.toMillis() * BACKOFF_JITTER);
        Duration delay = base.plusMillis(jitterMillis == 0 ? 0 : random.nextLong(-jitterMillis, jitterMillis + 1));
        if (retryAfter != null && retryAfter.compareTo(delay) > 0) {
            delay = retryAfter.compareTo(FURTHEST_AHEAD) > 0 ? FURTHEST_AHEAD : retryAfter;
        }
        return now.plus(delay);
    }

    public Instant clamp(Instant stored, Instant now) {
        return stored.isAfter(now.plus(FURTHEST_AHEAD)) ? afterSuccess(now) : stored;
    }

    public boolean due(@Nullable Instant next, Instant now) {
        return next != null && !next.isAfter(now);
    }

    private Duration between(Duration low, Duration high) {
        return Duration.ofMillis(random.nextLong(low.toMillis(), high.toMillis() + 1));
    }
}

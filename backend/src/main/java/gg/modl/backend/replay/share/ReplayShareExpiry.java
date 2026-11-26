package gg.modl.backend.replay.share;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

public enum ReplayShareExpiry {
    ONE_DAY(Duration.ofDays(1)),
    SEVEN_DAYS(Duration.ofDays(7)),
    THIRTY_DAYS(Duration.ofDays(30)),
    NEVER(null);

    private final @Nullable Duration lifetime;

    ReplayShareExpiry(@Nullable Duration lifetime) {
        this.lifetime = lifetime;
    }

    public Optional<Instant> expiresAt(Instant createdAt) {
        return Optional.ofNullable(lifetime).map(createdAt::plus);
    }
}

package gg.modl.backend.replay.data;

import java.time.Instant;
import org.jspecify.annotations.Nullable;

public record ReplayShare(
    String id,
    String replayId,
    String tokenHash,
    String createdBy,
    Instant createdAt,
    @Nullable Instant expiresAt,
    @Nullable Instant revokedAt,
    long viewCount,
    @Nullable Instant lastViewedAt
) {
    public boolean opensAt(Instant now) {
        return revokedAt == null && (expiresAt == null || now.isBefore(expiresAt));
    }
}

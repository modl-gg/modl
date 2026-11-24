package gg.modl.backend.player.dto.response;

import org.jspecify.annotations.Nullable;
import java.util.List;

public record PlayerStandingView(
    String socialStatus,
    String gameplayStatus,
    int socialPoints,
    int gameplayPoints,
    List<Entry> punishments
) {
    public enum State {
        PARDONED,
        ROLLED_BACK,
        QUEUED,
        ACTIVE,
        INACTIVE
    }

    public record Entry(
        String id,
        String type,
        String category,
        long issuedAt,
        @Nullable Long durationMs,
        State state,
        @Nullable Long endedAt,
        @Nullable Long expiresAt,
        @Nullable String reason
    ) {
    }
}

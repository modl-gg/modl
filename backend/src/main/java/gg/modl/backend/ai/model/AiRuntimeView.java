package gg.modl.backend.ai.model;

import org.jspecify.annotations.Nullable;
import java.time.Instant;

public record AiRuntimeView(
    @Nullable Instant lastSuccessAt,
    @Nullable AiFailureKind lastFailure,
    @Nullable Instant lastFailureAt,
    @Nullable Instant pausedUntil,
    int callsThisHour,
    int callsToday
) {
}

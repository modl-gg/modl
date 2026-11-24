package gg.modl.backend.ai.model;

import org.jspecify.annotations.Nullable;
import java.time.Duration;

public record AiCallFailure(
    AiFailureKind kind,
    boolean retryable,
    @Nullable Duration retryAfter,
    @Nullable Integer status,
    @Nullable AiShapeRejection rejection
) {
    public static AiCallFailure of(AiFailureKind kind) {
        return new AiCallFailure(kind, false, null, null, null);
    }

    public static AiCallFailure retryable(AiFailureKind kind) {
        return new AiCallFailure(kind, true, null, null, null);
    }

    public AiCallFailure withStatus(int httpStatus) {
        return new AiCallFailure(kind, retryable, retryAfter, httpStatus, rejection);
    }
}

package gg.modl.backend.ai.model;

public enum AiFailureKind {
    AUTH(BreakerEffect.OPEN_NOW),
    PERMISSION(BreakerEffect.OPEN_NOW),
    QUOTA(BreakerEffect.OPEN_NOW),
    RATE_LIMITED(BreakerEffect.COUNT),
    MODEL_NOT_FOUND(BreakerEffect.OPEN_NOW),
    INVALID_REQUEST(BreakerEffect.COUNT),
    CONNECT(BreakerEffect.COUNT),
    TLS(BreakerEffect.COUNT),
    TIMEOUT(BreakerEffect.COUNT),
    BLOCKED_ADDRESS(BreakerEffect.OPEN_NOW),
    REDIRECT(BreakerEffect.COUNT),
    RESPONSE_TOO_LARGE(BreakerEffect.COUNT),
    INVALID_RESPONSE(BreakerEffect.NONE),
    PROVIDER_ERROR(BreakerEffect.COUNT),
    TRUNCATED(BreakerEffect.NONE),
    REFUSED(BreakerEffect.NONE),
    FILTERED(BreakerEffect.NONE),
    LIST_UNSUPPORTED(BreakerEffect.NONE),
    BUSY(BreakerEffect.SKIP),
    HOURLY_BUDGET(BreakerEffect.SKIP),
    DAILY_BUDGET(BreakerEffect.SKIP),
    REPORTER_LIMIT(BreakerEffect.SKIP);

    private final BreakerEffect breakerEffect;

    AiFailureKind(BreakerEffect breakerEffect) {
        this.breakerEffect = breakerEffect;
    }

    public BreakerEffect breakerEffect() {
        return breakerEffect;
    }

    public enum BreakerEffect {
        OPEN_NOW,
        COUNT,
        NONE,
        SKIP
    }
}

package gg.modl.backend.webhook;

public enum WebhookFailureReason {
    REJECTED,
    RATE_LIMITED,
    DISCORD_ERROR,
    UNREACHABLE,
    INVALID_URL
}

package gg.modl.backend.auth;

import gg.modl.backend.auth.data.AuthCode;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

public record LoginCodeLimits(Duration codeLifetime, Duration issueCooldown, int failureLimit, Duration failureWindow) {

    public static LoginCodeLimits from(AuthConfiguration configuration) {
        return new LoginCodeLimits(
            Duration.ofSeconds(configuration.getEmailCodeExpiry()),
            Duration.ofSeconds(configuration.getEmailCodeCooldownSeconds()),
            configuration.getEmailCodeFailureLimit(),
            Duration.ofSeconds(configuration.getEmailCodeFailureWindowSeconds())
        );
    }

    public Instant codeExpiry(Instant issuedAt) {
        return issuedAt.plus(codeLifetime);
    }

    public Instant cooldownThreshold(Instant now) {
        return now.minus(issueCooldown);
    }

    public Instant failureWindowThreshold(Instant now) {
        return now.minus(failureWindow);
    }

    public Instant failureWindowEnd(Instant start) {
        return start.plus(failureWindow);
    }

    public Optional<Instant> lockedUntil(AuthCode code, Instant now) {
        Instant windowStart = code.getFailureWindowStartedAt();
        if (windowStart == null || code.getRecentFailures() < failureLimit) {
            return Optional.empty();
        }
        Instant windowEnd = windowStart.plus(failureWindow);
        return windowEnd.isAfter(now) ? Optional.of(windowEnd) : Optional.empty();
    }
}

package gg.modl.backend.auth.repository;

import org.jspecify.annotations.Nullable;
import java.time.Instant;
import java.util.Optional;

public record StaffTotp(
    String email,
    @Nullable String secret,
    @Nullable Long lastUsedStep,
    @Nullable Instant enabledAt,
    @Nullable String pendingSecret,
    @Nullable Instant pendingExpiresAt
) {
    public boolean enabled() {
        return secret != null;
    }

    public Optional<String> pendingSecretAt(Instant now) {
        return pendingSecret != null && pendingExpiresAt != null && pendingExpiresAt.isAfter(now)
            ? Optional.of(pendingSecret)
            : Optional.empty();
    }

    @Override
    public String toString() {
        return "StaffTotp[email=" + email + ", enabled=" + enabled() + ", enabledAt=" + enabledAt + "]";
    }
}

package gg.modl.backend.auth.repository;

import gg.modl.backend.auth.session.AuthMethod;
import org.jspecify.annotations.Nullable;
import java.time.Instant;

public record PanelSession(
    String publicId,
    String email,
    String sessionKeyHash,
    @Nullable String ipAddress,
    @Nullable String userAgent,
    Instant createdAt,
    Instant expiresAt,
    AuthMethod authMethod,
    boolean secondFactorVerified
) {
}

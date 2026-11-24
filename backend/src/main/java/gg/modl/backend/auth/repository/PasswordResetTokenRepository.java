package gg.modl.backend.auth.repository;

import java.time.Instant;
import java.util.Optional;

public interface PasswordResetTokenRepository {
    void replace(String email, String tokenHash, Instant now, Instant expiresAt);

    Optional<PasswordResetToken> findActive(String tokenHash, Instant now);

    Optional<PasswordResetToken> consumeActive(String tokenHash, Instant now);

    void deleteByEmail(String email);
}

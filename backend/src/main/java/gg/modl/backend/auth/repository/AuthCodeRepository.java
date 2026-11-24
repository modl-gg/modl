package gg.modl.backend.auth.repository;

import gg.modl.backend.auth.data.AuthCode;
import java.time.Instant;
import java.util.Optional;

public interface AuthCodeRepository {
    Optional<AuthCode> find(String email);

    void lock(String email);

    void delete(String email);

    boolean issue(String email, String codeHash, Instant issuedAt, Instant codeExpiresAt, Instant previousIssuedBefore);

    Optional<AuthCode> consumeIfHashMatches(String email, String codeHash, Instant now, Instant failureWindowStartedAfter, int maxRecentFailures);

    void recordFailedAttempt(String email, Instant now, Instant failureWindowStartedAfter, Instant newWindowRetainedUntil);
}

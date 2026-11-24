package gg.modl.backend.auth.repository;

import java.time.Instant;
import java.util.Optional;
import java.util.Set;

public interface StaffCredentialRepository {
    Optional<String> findPasswordHash(String email);

    boolean insertIfAbsent(String email, String passwordHash, Instant now);

    boolean updateIfMatches(String email, String expectedHash, String passwordHash, Instant now);

    void replace(String email, String passwordHash, Instant now);

    void delete(String email);

    void move(String fromEmail, String toEmail, Instant now);

    Set<String> findEmails();
}

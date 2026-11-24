package gg.modl.backend.auth.repository;

import java.time.Instant;
import java.util.Collection;
import java.util.Optional;
import java.util.Set;

public interface StaffTotpRepository {
    Optional<StaffTotp> find(String email);

    boolean isEnabled(String email);

    void savePending(String email, String pendingSecret, Instant expiresAt, Instant now);

    boolean enablePending(String email, String pendingSecret, long step, Instant now);

    boolean advanceStep(String email, String secret, long step);

    void delete(String email);

    void move(String fromEmail, String toEmail);

    void replaceRecoveryCodes(String email, Collection<String> codeHashes, Instant now);

    boolean consumeRecoveryCode(String email, String codeHash);

    int countRecoveryCodes(String email);

    Set<String> findEnabledEmails();
}

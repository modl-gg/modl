package gg.modl.backend.auth.repository;

import gg.modl.backend.auth.data.WebAuthnCredential;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

public interface WebAuthnCredentialRepository {
    boolean insertIfAbsent(WebAuthnCredential credential);

    boolean existsByEmail(String email);

    int countByEmail(String email);

    Set<String> findEmails();

    List<WebAuthnCredential> findByEmail(String email);

    Optional<WebAuthnCredential> findByCredentialId(String credentialId);

    Optional<WebAuthnCredential> findByUserHandle(String userHandle);

    boolean updateUsage(String credentialId, long signatureCount, Instant lastUsedAt);

    boolean renameByIdAndEmail(String id, String email, String newName);

    boolean deleteByIdAndEmail(String id, String email);

    long deleteAllByEmail(String email);
}

package gg.modl.backend.storage.repository;

import java.util.Optional;

public interface EvidenceUploadTokenRepository {
    void insert(EvidenceUploadToken token);

    Optional<EvidenceUploadToken> findByTokenHash(String tokenHash);

    boolean deleteByTokenHash(String tokenHash);
}

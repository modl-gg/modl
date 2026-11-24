package gg.modl.backend.auth.repository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;

public interface PanelSessionRepository {
    void insert(PanelSession session);

    Optional<PanelSession> findByPublicId(String publicId);

    List<PanelSession> findByEmail(String email, Instant now);

    Set<String> findActivePublicIds(Collection<String> publicIds, Instant now, boolean secondFactorVerifiedOnly);

    boolean refresh(String publicId, Instant expiresAt);

    boolean markSecondFactorVerified(String publicId);

    int clearSecondFactorVerified(String email);

    boolean deleteByPublicId(String publicId);

    int deleteByPublicIds(Collection<String> publicIds);

    int deleteByEmail(String email);

    int deleteByEmailExcept(String email, String keptPublicId);
}

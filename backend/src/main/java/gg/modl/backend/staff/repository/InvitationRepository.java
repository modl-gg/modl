package gg.modl.backend.staff.repository;

import gg.modl.backend.staff.data.Invitation;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface InvitationRepository {
    void insert(Invitation invitation);

    Optional<Invitation> findById(String id);

    List<Invitation> findActive(Instant now);

    long countActive(Instant now);

    boolean existsActiveByEmail(String email, Instant now);

    Optional<Invitation> findActiveByTokenHash(String tokenHash, Instant now);

    Optional<Invitation> consumeActive(String tokenHash, Instant now);

    boolean deleteById(String id);

    boolean rotateToken(String id, String expectedTokenHash, String tokenHash, Instant newExpiresAt, Instant updatedAt);
}

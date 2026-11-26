package gg.modl.backend.replay.repository;

import gg.modl.backend.replay.data.ReplayShare;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;

public interface ReplayShareRepository {
    boolean insertWithinLimit(ReplayShare share, int openLimit, int closedKept, Instant now);

    List<ReplayShare> listForReplay(String replayId);

    Optional<ReplayShare> findForReplay(String replayId, String shareId);

    Optional<ReplayShare> findByTokenHash(String tokenHash);

    boolean revoke(String shareId, Instant revokedAt);

    void addViews(String shareId, long views, Instant lastViewedAt);

    Set<String> findReplayIdsWithOpenShare(Collection<String> replayIds, Instant now);
}

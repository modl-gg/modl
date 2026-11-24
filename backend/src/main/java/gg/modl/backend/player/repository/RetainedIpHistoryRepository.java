package gg.modl.backend.player.repository;

import java.time.Instant;
import java.util.Optional;

public interface RetainedIpHistoryRepository {
    Optional<Instant> latestRetainedSighting();

    boolean anyRetained();

    int releaseBatch(int limit);
}

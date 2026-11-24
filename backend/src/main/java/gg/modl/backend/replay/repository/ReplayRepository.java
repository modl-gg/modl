package gg.modl.backend.replay.repository;

import gg.modl.backend.replay.data.ReplayDocument;
import gg.modl.backend.replay.data.ReplayLabel;
import org.jspecify.annotations.Nullable;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface ReplayRepository {
    void insert(ReplayDocument replay);

    boolean updateUpload(String replayId, String status, long fileSize);

    Optional<ReplayDocument> findById(String replayId);

    Optional<ReplayDocument> replaceLabels(String replayId, List<ReplayLabel> labels);

    List<ReplayDocument> findByTargetUuid(String targetUuid, int limit);

    List<ReplayDocument> findExpiredWithStorageKey(Instant cutoff, @Nullable ReplayCursor after, int limit);

    List<ReplayDocument> findExpiredWithMissingStorageKey(Instant cutoff, @Nullable ReplayCursor after, int limit);

    List<ReplayDocument> findByStorageKeys(Collection<String> storageKeys);

    long deleteByIds(Collection<String> replayIds);
}

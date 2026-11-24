package gg.modl.backend.storage.repository;

import gg.modl.backend.storage.UploadOrigin;
import org.jspecify.annotations.Nullable;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface StorageFileRepository {
    boolean insertIfAbsent(StorageFile file, UploadOrigin origin, @Nullable Instant unattachedExpiresAtOrNull);

    int markAttached(Collection<String> storageKeys);

    List<String> findUnattachedExpired(Instant now, int limit);

    Optional<StorageFile> findByKey(String key);

    List<StorageFile> findByKeys(Collection<String> keys);

    boolean hasAnyFile();

    void deleteByKeys(Collection<String> keys);

    long sumSizeSince(UploadOrigin origin, Instant createdAfter);

    PrefixUsage usageUnder(Collection<String> prefixes);

    long sumTotalSize();
}

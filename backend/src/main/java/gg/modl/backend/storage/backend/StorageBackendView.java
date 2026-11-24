package gg.modl.backend.storage.backend;

import gg.modl.backend.storage.StorageProperties;
import gg.modl.backend.storage.StorageType;
import java.util.List;
import java.util.Optional;

public record StorageBackendView(
    StorageType active,
    boolean managed,
    StorageProperties.S3 s3,
    Optional<StorageType> migratingTo,
    Optional<StorageTransfer> latestTransfer,
    List<StorageType> oldCopies,
    boolean removingOldCopies
) {
    public StorageBackendView {
        oldCopies = List.copyOf(oldCopies);
    }
}

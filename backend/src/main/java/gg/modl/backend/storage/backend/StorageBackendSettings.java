package gg.modl.backend.storage.backend;

import gg.modl.backend.storage.StorageProperties;
import gg.modl.backend.storage.StorageType;
import org.jspecify.annotations.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public record StorageBackendSettings(
    StorageType active,
    @Nullable StorageType migratingTo,
    StorageProperties.S3 s3,
    List<String> formerPublicBaseUrls,
    List<StorageBackendAddress> oldCopies
) {
    public static final StorageBackendSettings DEFAULTS = managedBy(StorageType.LOCAL);
    static final int MAX_FORMER_PUBLIC_BASE_URLS = 10;
    static final int MAX_OLD_COPIES = 4;

    public StorageBackendSettings {
        active = active == null ? StorageType.LOCAL : active;
        s3 = s3 == null ? StorageProperties.S3.EMPTY : s3;
        formerPublicBaseUrls = formerPublicBaseUrls == null ? List.of() : List.copyOf(formerPublicBaseUrls);
        oldCopies = oldCopies == null ? List.of() : List.copyOf(oldCopies);
    }

    public static StorageBackendSettings managedBy(StorageType type) {
        return new StorageBackendSettings(type, null, StorageProperties.S3.EMPTY, List.of(), List.of());
    }

    public Optional<StorageType> migrationTarget() {
        return Optional.ofNullable(migratingTo);
    }

    public boolean holdsEveryUploadInTheLocalFolder() {
        return active == StorageType.LOCAL && migratingTo == null;
    }

    public boolean usesS3() {
        return active == StorageType.S3 || migratingTo == StorageType.S3;
    }

    public boolean usesLocalFolder() {
        return active == StorageType.LOCAL || migratingTo == StorageType.LOCAL;
    }

    StorageBackendAddress activeAddress() {
        return StorageBackendAddress.of(active, s3);
    }

    List<StorageBackendAddress> liveAddresses() {
        List<StorageBackendAddress> live = new ArrayList<>();
        live.add(activeAddress());
        migrationTarget().map(target -> StorageBackendAddress.of(target, s3)).ifPresent(live::add);
        return List.copyOf(live);
    }

    boolean hasRoomForAnotherOldCopy() {
        return oldCopies.size() < MAX_OLD_COPIES;
    }

    StorageBackendSettings withS3(StorageProperties.S3 connection) {
        return new StorageBackendSettings(active, migratingTo, connection, formerPublicBaseUrls, oldCopies);
    }

    StorageBackendSettings migrating(StorageType source, StorageType target) {
        return new StorageBackendSettings(source, target, s3, formerPublicBaseUrls, oldCopies);
    }

    StorageBackendSettings migrated(@Nullable String previousPublicBaseUrl) {
        StorageType target = migratingTo;
        if (target == null) {
            return this;
        }
        List<String> former = new ArrayList<>(formerPublicBaseUrls);
        if (previousPublicBaseUrl != null && !previousPublicBaseUrl.isEmpty() && !former.contains(previousPublicBaseUrl)) {
            former.addFirst(previousPublicBaseUrl);
        }
        StorageBackendAddress moved = StorageBackendAddress.of(target, s3);
        StorageBackendAddress left = activeAddress();
        List<StorageBackendAddress> copies = new ArrayList<>(oldCopies.stream()
            .filter(copy -> !copy.sameBackend(moved) && !copy.sameBackend(left))
            .toList());
        copies.addFirst(left);
        return new StorageBackendSettings(target, null, s3, former.subList(0, Math.min(former.size(), MAX_FORMER_PUBLIC_BASE_URLS)),
            copies);
    }

    StorageBackendSettings withoutOldCopy(StorageBackendAddress removed) {
        return new StorageBackendSettings(active, migratingTo, s3, formerPublicBaseUrls,
            oldCopies.stream().filter(copy -> !copy.sameBackend(removed)).toList());
    }
}

package gg.modl.backend.storage.backend;

import gg.modl.backend.storage.StorageProperties;
import gg.modl.backend.storage.StorageType;
import org.jspecify.annotations.Nullable;

public record StorageBackendAddress(StorageType kind, StorageProperties.@Nullable S3 s3) {
    public StorageBackendAddress {
        s3 = kind == StorageType.S3 ? (s3 == null ? StorageProperties.S3.EMPTY : s3) : null;
    }

    static StorageBackendAddress of(StorageType kind, StorageProperties.S3 s3) {
        return new StorageBackendAddress(kind, s3);
    }

    boolean sameBackend(StorageBackendAddress other) {
        if (kind != other.kind) {
            return false;
        }
        return kind == StorageType.LOCAL || connection().sameBucket(other.connection());
    }

    StorageProperties.S3 connection() {
        return s3 == null ? StorageProperties.S3.EMPTY : s3;
    }
}

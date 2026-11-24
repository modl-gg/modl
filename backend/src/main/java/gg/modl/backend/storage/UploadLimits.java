package gg.modl.backend.storage;

public interface UploadLimits {
    long BYTES_PER_MB = 1024L * 1024;
    long BYTES_PER_GB = 1024L * BYTES_PER_MB;

    long maxUploadBytes();

    long maxStorageBytes();

    default boolean limitsTotalStorage() {
        return maxStorageBytes() > 0;
    }

    static UploadLimits of(long maxUploadMb, long maxStorageGb) {
        return new UploadLimits() {
            @Override
            public long maxUploadBytes() {
                return maxUploadMb * BYTES_PER_MB;
            }

            @Override
            public long maxStorageBytes() {
                return maxStorageGb * BYTES_PER_GB;
            }
        };
    }
}

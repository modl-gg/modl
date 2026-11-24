package gg.modl.backend.storage.s3;

import gg.modl.backend.storage.StorageProperties;
import java.time.Clock;

public final class S3StorageFactory {
    private S3StorageFactory() {
    }

    public static S3ObjectStorage create(StorageProperties.S3 settings, Clock clock) {
        String bucket = settings.bucket();
        if (bucket == null || !settings.complete()) {
            throw new IllegalStateException("S3 storage needs " + String.join(", ", settings.missingSettings()));
        }
        return new S3ObjectStorage(
            S3ClientFactory.createClient(settings),
            S3ClientFactory.createPresigner(settings),
            bucket,
            settings.publicBaseUrl(),
            clock
        );
    }
}

package gg.modl.backend.storage.backend;

import gg.modl.backend.storage.ObjectStorage;
import gg.modl.backend.storage.StorageProperties;
import gg.modl.backend.storage.StorageType;
import gg.modl.backend.storage.local.LocalObjectStorage;
import gg.modl.backend.storage.s3.S3ObjectStorage;
import gg.modl.backend.storage.s3.S3StorageFactory;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;

@Slf4j
final class StorageBackends implements AutoCloseable {
    private final LocalObjectStorage local;
    private final Clock clock;
    private final Map<StorageProperties.S3, S3ObjectStorage> opened = new LinkedHashMap<>();

    StorageBackends(LocalObjectStorage local, Clock clock) {
        this.local = local;
        this.clock = clock;
    }

    synchronized ObjectStorage backend(StorageType kind, StorageProperties.S3 settings) {
        return switch (kind) {
            case LOCAL -> local;
            case S3 -> opened.computeIfAbsent(settings, connection -> S3StorageFactory.create(connection, clock));
        };
    }

    ObjectStorage backend(StorageBackendAddress address) {
        return backend(address.kind(), address.connection());
    }

    synchronized void retireUnused(StorageRoute route) {
        List<ObjectStorage> used = route.inUse();
        opened.values().removeIf(storage -> {
            if (used.contains(storage)) {
                return false;
            }
            close(storage);
            return true;
        });
    }

    @Override
    public synchronized void close() {
        opened.values().forEach(StorageBackends::close);
        opened.clear();
    }

    private static void close(S3ObjectStorage storage) {
        try {
            storage.close();
        } catch (RuntimeException e) {
            log.debug("Could not close an S3 client", e);
        }
    }
}

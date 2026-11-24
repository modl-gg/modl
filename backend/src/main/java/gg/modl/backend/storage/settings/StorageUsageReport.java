package gg.modl.backend.storage.settings;

import gg.modl.backend.database.DataDirectoryProperties;
import gg.modl.backend.database.UnitOfWork;
import gg.modl.backend.infrastructure.disk.DiskSpaceFloor;
import gg.modl.backend.storage.StorageType;
import gg.modl.backend.storage.UploadLimits;
import gg.modl.backend.storage.repository.PrefixUsage;
import gg.modl.backend.storage.repository.StorageFileRepository;
import java.io.IOException;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class StorageUsageReport {
    private static final long UNKNOWN_CAPACITY = 0L;
    private static final Duration SNAPSHOT_LIFETIME = Duration.ofMinutes(1);

    private final StorageFileRepository storageFileRepository;
    private final UnitOfWork unitOfWork;
    private final UploadLimits uploadLimits;
    private final DataDirectoryProperties dataDirectory;
    private final DiskSpaceFloor diskSpaceFloor;
    private final Clock clock;
    private final AtomicReference<Snapshot> cachedSnapshot = new AtomicReference<>();

    @Autowired
    public StorageUsageReport(StorageFileRepository storageFileRepository, UnitOfWork unitOfWork, UploadLimits uploadLimits,
                              DataDirectoryProperties dataDirectory, Clock clock) {
        this(storageFileRepository, unitOfWork, uploadLimits, dataDirectory, DiskSpaceFloor.DEFAULT, clock);
    }

    StorageUsageReport(StorageFileRepository storageFileRepository, UnitOfWork unitOfWork, UploadLimits uploadLimits,
                       DataDirectoryProperties dataDirectory, DiskSpaceFloor diskSpaceFloor, Clock clock) {
        this.storageFileRepository = storageFileRepository;
        this.unitOfWork = unitOfWork;
        this.uploadLimits = uploadLimits;
        this.dataDirectory = dataDirectory;
        this.diskSpaceFloor = diskSpaceFloor;
        this.clock = clock;
    }

    private record Snapshot(List<StorageUsage.GroupUsage> groups, long usedBytes, Instant computedAt) {
    }

    public StorageUsage current(StorageType active) {
        Snapshot snapshot = snapshot();
        return new StorageUsage(snapshot.groups(), snapshot.usedBytes(), capacity(active, snapshot.usedBytes()));
    }

    private Snapshot snapshot() {
        Instant now = clock.instant();
        Snapshot cached = cachedSnapshot.get();
        if (cached != null && cached.computedAt().plus(SNAPSHOT_LIFETIME).isAfter(now)) {
            return cached;
        }
        Snapshot computed = unitOfWork.report(() -> computeSnapshot(now));
        cachedSnapshot.set(computed);
        return computed;
    }

    private Snapshot computeSnapshot(Instant now) {
        List<StorageUsage.GroupUsage> groups = new ArrayList<>();
        PrefixUsage grouped = new PrefixUsage(0, 0);
        for (StorageUsageGroup group : StorageUsageGroup.values()) {
            if (group == StorageUsageGroup.OTHER) {
                continue;
            }
            PrefixUsage usage = storageFileRepository.usageUnder(group.keyPrefixes());
            grouped = grouped.plus(usage);
            groups.add(new StorageUsage.GroupUsage(group, usage.files(), usage.bytes()));
        }
        long used = storageFileRepository.sumTotalSize();
        groups.add(new StorageUsage.GroupUsage(StorageUsageGroup.OTHER, 0L, Math.max(0L, used - grouped.bytes())));
        return new Snapshot(List.copyOf(groups), used, now);
    }

    private long capacity(StorageType active, long used) {
        long limit = uploadLimits.limitsTotalStorage() ? uploadLimits.maxStorageBytes() : UNKNOWN_CAPACITY;
        if (active != StorageType.LOCAL) {
            return limit;
        }
        long disk = used + writableSpace(dataDirectory.storage());
        return limit == UNKNOWN_CAPACITY ? disk : Math.min(limit, disk);
    }

    private long writableSpace(Path path) {
        try {
            Path existing = path;
            while (existing != null && !Files.exists(existing)) {
                existing = existing.getParent();
            }
            if (existing == null) {
                return 0L;
            }
            FileStore store = Files.getFileStore(existing);
            return diskSpaceFloor.writableBytes(store);
        } catch (IOException e) {
            return 0L;
        }
    }
}

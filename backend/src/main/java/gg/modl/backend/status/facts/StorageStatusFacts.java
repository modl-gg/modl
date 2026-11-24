package gg.modl.backend.status.facts;

import gg.modl.backend.database.DataDirectoryProperties;
import gg.modl.backend.infrastructure.disk.DiskSpaceFloor;
import gg.modl.backend.infrastructure.util.ByteFormatUtil;
import gg.modl.backend.limits.OperatorLimits;
import gg.modl.backend.status.StatusContributor;
import gg.modl.backend.status.StatusFact;
import gg.modl.backend.status.StatusItem;
import gg.modl.backend.status.StatusSection;
import gg.modl.backend.storage.ConfirmedStorageTotal;
import gg.modl.backend.storage.StorageType;
import gg.modl.backend.storage.backend.StorageBackendService;
import gg.modl.backend.storage.backend.StorageBackendView;
import gg.modl.backend.storage.backend.StorageTransfer;
import java.io.IOException;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class StorageStatusFacts implements StatusContributor {
    private static final DiskSpaceFloor FLOOR = DiskSpaceFloor.DEFAULT;

    private final StorageBackendService backends;
    private final ConfirmedStorageTotal storageTotal;
    private final OperatorLimits limits;
    private final DataDirectoryProperties dataDirectory;

    @Override
    public StatusSection section() {
        return StatusSection.STORAGE;
    }

    @Override
    public List<StatusFact> facts() {
        StorageBackendView view = backends.view();
        return List.of(backend(view), used(), transfer(view), diskFree());
    }

    private static StatusFact backend(StorageBackendView view) {
        String where = view.active() == StorageType.S3 ? "S3" : "the data directory";
        return StatusItem.STORAGE_BACKEND.ok(where + (view.managed() ? " (set by configuration)" : ""));
    }

    private StatusFact used() {
        String used = ByteFormatUtil.formatCompact(storageTotal.bytes());
        if (!limits.limitsTotalStorage()) {
            return StatusItem.STORAGE_USED.ok(used + ", no limit");
        }
        String text = used + " of " + ByteFormatUtil.formatCompact(limits.maxStorageBytes());
        return storageTotal.bytes() * 10 >= limits.maxStorageBytes() * 9 ? StatusItem.STORAGE_USED.warning(text) : StatusItem.STORAGE_USED.ok(text);
    }

    private static StatusFact transfer(StorageBackendView view) {
        if (view.latestTransfer().isEmpty()) {
            return StatusItem.STORAGE_TRANSFER.ok("none");
        }
        StorageTransfer latest = view.latestTransfer().get();
        String progress = latest.counts().copiedObjects() + " copied, " + latest.counts().skippedObjects() + " already there, "
            + latest.counts().failedObjects() + " failed";
        return switch (latest.state()) {
            case RUNNING -> StatusItem.STORAGE_TRANSFER.warning("moving to " + latest.target() + ": " + progress);
            case FAILED -> StatusItem.STORAGE_TRANSFER.problem("stopped: " + progress);
            case DONE -> StatusItem.STORAGE_TRANSFER.ok("finished " + StatusFormats.instant(latest.updatedAt()));
        };
    }

    private StatusFact diskFree() {
        Path root = dataDirectory.root();
        try {
            return diskFree(Files.getFileStore(Files.exists(root) ? root : root.toAbsolutePath().getParent()));
        } catch (IOException e) {
            return StatusItem.DISK_FREE.warning("unknown");
        }
    }

    static StatusFact diskFree(FileStore store) throws IOException {
        String text = ByteFormatUtil.formatCompact(store.getUsableSpace()) + " of " + ByteFormatUtil.formatCompact(store.getTotalSpace());
        if (!FLOOR.admits(store, 0)) {
            return StatusItem.DISK_FREE.problem(text + ", below the free-space floor: uploads and backups are refused");
        }
        return FLOOR.admits(store, FLOOR.minimumFreeBytes()) ? StatusItem.DISK_FREE.ok(text) : StatusItem.DISK_FREE.warning(text);
    }
}

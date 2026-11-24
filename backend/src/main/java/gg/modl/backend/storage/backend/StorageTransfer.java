package gg.modl.backend.storage.backend;

import gg.modl.backend.storage.StorageType;
import org.jspecify.annotations.Nullable;
import java.time.Instant;

public record StorageTransfer(
    String id,
    StorageType source,
    StorageType target,
    StorageTransferState state,
    TransferCounts counts,
    @Nullable String lastError,
    Instant startedAt,
    Instant updatedAt,
    @Nullable Instant finishedAt
) {
    static final int MAX_ERROR_LENGTH = 1000;

    public StorageTransfer {
        lastError = lastError == null ? null : truncated(lastError);
    }

    static StorageTransfer started(String id, StorageType source, StorageType target, Instant now) {
        return new StorageTransfer(id, source, target, StorageTransferState.RUNNING, TransferCounts.NONE, null, now, now, null);
    }

    StorageTransfer progressed(TransferCounts latest, @Nullable String error, Instant now) {
        return new StorageTransfer(id, source, target, state, latest, error != null ? error : lastError, startedAt, now, finishedAt);
    }

    StorageTransfer restarted(Instant now) {
        return new StorageTransfer(id, source, target, StorageTransferState.RUNNING, TransferCounts.NONE, null, startedAt, now, null);
    }

    StorageTransfer finished(StorageTransferState outcome, Instant now) {
        return new StorageTransfer(id, source, target, outcome, counts, lastError, startedAt, now, now);
    }

    public boolean isRunning() {
        return state == StorageTransferState.RUNNING;
    }

    private static String truncated(String error) {
        return error.length() <= MAX_ERROR_LENGTH ? error : error.substring(0, MAX_ERROR_LENGTH);
    }

    public record TransferCounts(long copiedObjects, long copiedBytes, long skippedObjects, long failedObjects) {
        public static final TransferCounts NONE = new TransferCounts(0, 0, 0, 0);

        TransferCounts copied(long bytes) {
            return new TransferCounts(copiedObjects + 1, copiedBytes + bytes, skippedObjects, failedObjects);
        }

        TransferCounts skipped() {
            return new TransferCounts(copiedObjects, copiedBytes, skippedObjects + 1, failedObjects);
        }

        TransferCounts failed() {
            return new TransferCounts(copiedObjects, copiedBytes, skippedObjects, failedObjects + 1);
        }

        long handled() {
            return copiedObjects + skippedObjects + failedObjects;
        }
    }
}

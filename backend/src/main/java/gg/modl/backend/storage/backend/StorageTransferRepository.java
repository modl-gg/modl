package gg.modl.backend.storage.backend;

import java.util.Optional;

public interface StorageTransferRepository {
    void insert(StorageTransfer transfer);

    void save(StorageTransfer transfer);

    boolean saveWhileRunning(StorageTransfer transfer);

    Optional<StorageTransfer> latest();

    int deleteFinishedBeyondNewest(int kept);
}

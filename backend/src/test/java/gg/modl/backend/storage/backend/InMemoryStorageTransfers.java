package gg.modl.backend.storage.backend;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

final class InMemoryStorageTransfers implements StorageTransferRepository {
    private final List<StorageTransfer> rows = new ArrayList<>();
    private int saves;

    @Override
    public synchronized void insert(StorageTransfer transfer) {
        rows.add(transfer);
    }

    @Override
    public synchronized void save(StorageTransfer transfer) {
        rows.replaceAll(row -> row.id().equals(transfer.id()) ? transfer : row);
        saves++;
    }

    @Override
    public synchronized boolean saveWhileRunning(StorageTransfer transfer) {
        boolean running = rows.stream().anyMatch(row -> row.id().equals(transfer.id()) && row.isRunning());
        if (running) {
            save(transfer);
        }
        return running;
    }

    @Override
    public synchronized Optional<StorageTransfer> latest() {
        return rows.stream().max(Comparator.comparing(StorageTransfer::startedAt).thenComparing(StorageTransfer::id));
    }

    @Override
    public synchronized int deleteFinishedBeyondNewest(int kept) {
        return 0;
    }

    synchronized int saves() {
        return saves;
    }
}

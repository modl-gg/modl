package gg.modl.backend.storage;

import gg.modl.backend.storage.repository.StorageFileRepository;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class ConfirmedStorageTotal {
    private static final long UNKNOWN = -1L;

    private final StorageFileRepository storageFileRepository;
    private final AtomicLong bytes = new AtomicLong(UNKNOWN);
    private final ReentrantLock loadLock = new ReentrantLock();

    public long bytes() {
        long known = bytes.get();
        if (known != UNKNOWN) {
            return known;
        }
        loadLock.lock();
        try {
            if (bytes.get() == UNKNOWN) {
                bytes.set(storageFileRepository.sumTotalSize());
            }
            return bytes.get();
        } finally {
            loadLock.unlock();
        }
    }

    public void added(long size) {
        adjust(size);
    }

    public void removed(long size) {
        adjust(-size);
    }

    public void resync() {
        loadLock.lock();
        try {
            bytes.set(storageFileRepository.sumTotalSize());
        } finally {
            loadLock.unlock();
        }
    }

    private void adjust(long delta) {
        bytes.updateAndGet(current -> current == UNKNOWN ? UNKNOWN : Math.max(0L, current + delta));
    }
}

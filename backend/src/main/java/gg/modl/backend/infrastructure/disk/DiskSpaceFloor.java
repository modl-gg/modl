package gg.modl.backend.infrastructure.disk;

import java.io.IOException;
import java.nio.file.FileStore;

public record DiskSpaceFloor(long minimumFreeBytes, double minimumFreeFraction) {
    public static final DiskSpaceFloor DEFAULT = new DiskSpaceFloor(1024L * 1024 * 1024, 0.05);

    public boolean admits(FileStore store, long incomingBytes) throws IOException {
        return shortfall(store, incomingBytes) == 0;
    }

    public long shortfall(FileStore store, long incomingBytes) throws IOException {
        long remaining = store.getUsableSpace() - Math.max(incomingBytes, 0L);
        return Math.max(0L, floorOf(store) - remaining);
    }

    public long writableBytes(FileStore store) throws IOException {
        return Math.max(0L, store.getUsableSpace() - floorOf(store));
    }

    private long floorOf(FileStore store) throws IOException {
        return Math.max(minimumFreeBytes, (long) (store.getTotalSpace() * minimumFreeFraction));
    }
}

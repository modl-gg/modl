package gg.modl.backend.backup;

import gg.modl.backend.infrastructure.disk.DiskSpaceFloor;
import gg.modl.backend.infrastructure.util.ByteFormatUtil;
import java.io.IOException;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;

public final class BackupDiskSpace {
    public static final BackupDiskSpace SYSTEM = new BackupDiskSpace(DiskSpaceFloor.DEFAULT, Files::getFileStore);

    private static final long MARGIN_BYTES = 64L * 1024 * 1024;

    private final DiskSpaceFloor floor;
    private final FileStoreLookup fileStores;

    public BackupDiskSpace(DiskSpaceFloor floor, FileStoreLookup fileStores) {
        this.floor = floor;
        this.fileStores = fileStores;
    }

    public void require(Path directory, long archiveBytes) throws IOException {
        Check check = check(directory, archiveBytes);
        if (!check.sufficient()) {
            throw check.failure();
        }
    }

    Check check(Path directory, long archiveBytes) throws IOException {
        Files.createDirectories(directory);
        FileStore store = fileStores.of(directory);
        long shortfall = floor.shortfall(store, Math.max(archiveBytes, 0L) + MARGIN_BYTES);
        return new Check(directory, store.getUsableSpace(), shortfall);
    }

    @FunctionalInterface
    public interface FileStoreLookup {
        FileStore of(Path path) throws IOException;
    }

    record Check(Path directory, long usableBytes, long shortfallBytes) {

        boolean sufficient() {
            return shortfallBytes == 0;
        }

        IOException failure() {
            return new IOException("not enough free disk space in " + directory + " (" + ByteFormatUtil.format(usableBytes)
                + " free, about " + ByteFormatUtil.format(usableBytes + shortfallBytes) + " needed)");
        }
    }
}

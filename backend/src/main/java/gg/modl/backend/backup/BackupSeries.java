package gg.modl.backend.backup;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class BackupSeries {
    private final BackupFiles files;
    private final BackupDiskSpace diskSpace;
    private final String prefix;
    private final int retained;

    public BackupSeries(BackupFiles files, BackupDiskSpace diskSpace, String prefix, int retained) {
        if (retained < 1) {
            throw new IllegalArgumentException("A backup series keeps at least one backup");
        }
        this.files = files;
        this.diskSpace = diskSpace;
        this.prefix = prefix;
        this.retained = retained;
    }

    public Path directory() {
        return files.directory();
    }

    public WrittenBackup write(long estimatedBytes, ArchiveWriter writer) throws IOException {
        Path target = files.next(prefix);
        List<Path> removed = makeRoomFor(estimatedBytes);
        try {
            BackupFiles.writeAtomically(target, writer);
        } catch (IOException e) {
            throw afterRemoving(removed, e);
        }
        try {
            files.prune(prefix, retained);
        } catch (IOException e) {
            throw new IOException("the backup " + target + " was written, but older backups could not be removed: " + e.getMessage(), e);
        }
        return new WrittenBackup(target, removed);
    }

    private List<Path> makeRoomFor(long estimatedBytes) throws IOException {
        BackupDiskSpace.Check check = diskSpace.check(files.directory(), estimatedBytes);
        if (check.sufficient()) {
            return List.of();
        }
        List<Path> released = oldestCovering(check.shortfallBytes());
        if (released.isEmpty()) {
            throw check.failure();
        }
        List<Path> removed = new ArrayList<>();
        for (Path archive : released) {
            try {
                Files.deleteIfExists(archive);
            } catch (IOException e) {
                throw afterRemoving(removed, e);
            }
            removed.add(archive);
        }
        BackupDiskSpace.Check afterRemoval = diskSpace.check(files.directory(), estimatedBytes);
        if (!afterRemoval.sufficient()) {
            throw afterRemoving(released, afterRemoval.failure());
        }
        return released;
    }

    private static IOException afterRemoving(List<Path> removed, IOException failure) {
        if (removed.isEmpty()) {
            return failure;
        }
        return new IOException(failure.getMessage() + " (older backups removed to make room: "
            + removed.stream().map(path -> path.getFileName().toString()).toList() + ")", failure);
    }

    private List<Path> oldestCovering(long bytes) throws IOException {
        List<Path> archives = files.archives(prefix);
        List<Path> released = new ArrayList<>();
        long reclaimed = 0;
        for (Path archive : archives.subList(Math.min(1, archives.size()), archives.size()).reversed()) {
            released.add(archive);
            reclaimed += Files.size(archive);
            if (reclaimed >= bytes) {
                return List.copyOf(released);
            }
        }
        return List.of();
    }

    public record WrittenBackup(Path archive, List<Path> removedToMakeRoom) {
        public WrittenBackup {
            removedToMakeRoom = List.copyOf(removedToMakeRoom);
        }
    }
}

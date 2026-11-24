package gg.modl.backend.database.sql;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

final class DurableFiles {
    private static final String POSIX = "posix";

    private DurableFiles() {
    }

    static void forceFile(Path file) throws IOException {
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.WRITE)) {
            channel.force(true);
        }
    }

    static void forceDirectory(Path directory) throws IOException {
        if (!directory.getFileSystem().supportedFileAttributeViews().contains(POSIX)) {
            return;
        }
        try (FileChannel channel = FileChannel.open(directory, StandardOpenOption.READ)) {
            channel.force(true);
        }
    }
}

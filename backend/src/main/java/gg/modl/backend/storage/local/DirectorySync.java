package gg.modl.backend.storage.local;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

@FunctionalInterface
interface DirectorySync {

    void force(Path directory) throws IOException;

    static DirectorySync probe(Path directory) {
        try {
            forceEntries(directory);
            return DirectorySync::forceEntries;
        } catch (IOException e) {
            return unsupported -> {
            };
        }
    }

    private static void forceEntries(Path directory) throws IOException {
        try (FileChannel channel = FileChannel.open(directory, StandardOpenOption.READ)) {
            channel.force(true);
        }
    }
}

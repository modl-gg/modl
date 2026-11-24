package gg.modl.backend.database.backup;

import gg.modl.backend.backup.FileTrees;
import java.io.Closeable;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;

public record WorkDirectory(Path path) implements Closeable {
    private static final Duration ABANDONED_AFTER = Duration.ofHours(12);

    public static WorkDirectory create(Path root, String prefix) throws IOException {
        Files.createDirectories(root);
        return new WorkDirectory(Files.createTempDirectory(root, prefix));
    }

    public static WorkDirectory createReplacingAbandoned(Path root, String prefix, Instant now) throws IOException {
        Files.createDirectories(root);
        Instant abandonedBefore = now.minus(ABANDONED_AFTER);
        for (Path child : FileTrees.children(root)) {
            if (child.getFileName().toString().startsWith(prefix) && FileTrees.modifiedBefore(child, abandonedBefore)) {
                FileTrees.deleteRecursively(child);
            }
        }
        return create(root, prefix);
    }

    @Override
    public void close() throws IOException {
        FileTrees.deleteRecursively(path);
    }
}

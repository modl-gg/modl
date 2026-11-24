package gg.modl.backend.backup;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

public final class FileTrees {

    private FileTrees() {
    }

    public static void deleteRecursively(Path directory) throws IOException {
        if (!Files.exists(directory)) {
            return;
        }
        List<Path> paths;
        try (Stream<Path> walk = Files.walk(directory)) {
            paths = walk.sorted(Comparator.reverseOrder()).toList();
        }
        for (Path path : paths) {
            Files.deleteIfExists(path);
        }
    }

    public static void deleteQuietly(Path directory, Exception primary) {
        try {
            deleteRecursively(directory);
        } catch (IOException e) {
            primary.addSuppressed(e);
        }
    }

    public static void deleteChildrenStartingWith(Path directory, String prefix) throws IOException {
        for (Path child : children(directory)) {
            if (child.getFileName().toString().startsWith(prefix)) {
                deleteRecursively(child);
            }
        }
    }

    public static boolean modifiedBefore(Path path, Instant moment) throws IOException {
        try {
            return Files.getLastModifiedTime(path).toInstant().isBefore(moment);
        } catch (NoSuchFileException e) {
            return false;
        }
    }

    public static boolean contains(Path root, Path path) throws IOException {
        return realPathOf(path.toAbsolutePath().normalize()).startsWith(realPathOf(root.toAbsolutePath().normalize()));
    }

    public static List<Path> children(Path directory) throws IOException {
        try (Stream<Path> entries = Files.list(directory)) {
            return entries.sorted().toList();
        }
    }

    private static Path realPathOf(Path path) throws IOException {
        Path existing = path;
        while (existing != null && !Files.exists(existing)) {
            existing = existing.getParent();
        }
        if (existing == null) {
            return path;
        }
        return existing.toRealPath().resolve(existing.relativize(path));
    }
}

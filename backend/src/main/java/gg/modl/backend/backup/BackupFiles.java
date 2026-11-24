package gg.modl.backend.backup;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

public final class BackupFiles {
    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private static final String ARCHIVE_SUFFIX = ".zip";
    private static final String PARTIAL_PREFIX = ".modl-backup-";
    private static final String PARTIAL_SUFFIX = ".partial";
    private static final Duration PARTIAL_ABANDONED_AFTER = Duration.ofHours(1);
    private static final Pattern TIMESTAMP_NAME = Pattern.compile("\\d{8}-\\d{6}");

    private final Path directory;
    private final Clock clock;

    public BackupFiles(Path directory, Clock clock) {
        this.directory = directory.toAbsolutePath().normalize();
        this.clock = clock;
    }

    public Path directory() {
        return directory;
    }

    public Path next(String prefix) {
        return directory.resolve(prefix + LocalDateTime.now(clock).format(TIMESTAMP) + ARCHIVE_SUFFIX);
    }

    public List<Path> archives(String prefix) throws IOException {
        if (!Files.isDirectory(directory)) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(directory)) {
            return files
                .filter(path -> isArchive(path, prefix))
                .sorted(Comparator.comparing((Path path) -> path.getFileName().toString()).reversed())
                .toList();
        }
    }

    public void prune(String prefix, int retained) throws IOException {
        for (Path expired : archives(prefix).stream().skip(retained).toList()) {
            Files.deleteIfExists(expired);
        }
    }

    public static void writeAtomically(Path target, ArchiveWriter writer) throws IOException {
        Path absoluteTarget = target.toAbsolutePath();
        Files.createDirectories(absoluteTarget.getParent());
        Path partial = Files.createTempFile(absoluteTarget.getParent(), PARTIAL_PREFIX, PARTIAL_SUFFIX);
        try {
            removeAbandonedPartials(partial);
            try (OutputStream output = Files.newOutputStream(partial)) {
                writer.writeTo(output);
            }
            try (FileChannel channel = FileChannel.open(partial, StandardOpenOption.WRITE)) {
                channel.force(true);
            }
            Files.move(partial, absoluteTarget, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } finally {
            Files.deleteIfExists(partial);
        }
    }

    private static void removeAbandonedPartials(Path justCreated) throws IOException {
        Instant abandonedBefore = Files.getLastModifiedTime(justCreated).toInstant().minus(PARTIAL_ABANDONED_AFTER);
        List<Path> partials;
        try (Stream<Path> files = Files.list(justCreated.getParent())) {
            partials = files.filter(BackupFiles::isPartial).toList();
        }
        for (Path partial : partials) {
            if (!partial.equals(justCreated) && FileTrees.modifiedBefore(partial, abandonedBefore)) {
                Files.deleteIfExists(partial);
            }
        }
    }

    private static boolean isPartial(Path path) {
        String name = path.getFileName().toString();
        return name.startsWith(PARTIAL_PREFIX) && name.endsWith(PARTIAL_SUFFIX) && Files.isRegularFile(path);
    }

    private static boolean isArchive(Path path, String prefix) {
        String name = path.getFileName().toString();
        return name.startsWith(prefix) && name.endsWith(ARCHIVE_SUFFIX)
            && TIMESTAMP_NAME.matcher(name.substring(prefix.length(), name.length() - ARCHIVE_SUFFIX.length())).matches()
            && Files.isRegularFile(path);
    }
}

package gg.modl.backend.migration.service;

import gg.modl.backend.infrastructure.disk.DurableFiles;
import java.io.IOException;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

public final class ImportUploadFiles {
    private static final String PREFIX = "migration-";
    private static final String ACCEPTED_SUFFIX = ".json";
    private static final String RESUMED_SUFFIX = ".resumed";
    private static final String PARTIAL_SUFFIX = ".part";
    private static final Pattern JOB_ID = Pattern.compile("[A-Za-z0-9-]{1,36}");

    private final Path directory;

    public ImportUploadFiles(Path directory) {
        this.directory = directory.toAbsolutePath();
    }

    FileStore store() throws IOException {
        Files.createDirectories(directory);
        return Files.getFileStore(directory);
    }

    Path createPartial(String jobId) throws IOException {
        Files.createDirectories(directory);
        return Files.createTempFile(directory, PREFIX + requireJobId(jobId) + "-", PARTIAL_SUFFIX);
    }

    void publish(Path partial, String jobId) throws IOException {
        move(partial, accepted(jobId));
    }

    Path markResumed(String jobId) throws IOException {
        Path resumed = resumed(jobId);
        move(accepted(jobId), resumed);
        return resumed;
    }

    List<Path> uploads() throws IOException {
        if (!Files.isDirectory(directory)) {
            return List.of();
        }
        try (Stream<Path> entries = Files.list(directory)) {
            return entries
                .filter(Files::isRegularFile)
                .filter(entry -> entry.getFileName().toString().startsWith(PREFIX))
                .toList();
        }
    }

    boolean belongsTo(Path upload, String jobId) {
        String name = upload.getFileName().toString();
        String own = PREFIX + jobId;
        return name.startsWith(own + "-") || name.startsWith(own + ".");
    }

    public Path accepted(String jobId) {
        return directory.resolve(PREFIX + requireJobId(jobId) + ACCEPTED_SUFFIX);
    }

    Path resumed(String jobId) {
        return directory.resolve(PREFIX + requireJobId(jobId) + RESUMED_SUFFIX);
    }

    private void move(Path source, Path target) throws IOException {
        Files.move(source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        DurableFiles.forceDirectory(directory);
    }

    private static String requireJobId(String jobId) {
        if (!JOB_ID.matcher(jobId).matches()) {
            throw new IllegalArgumentException("Not an import job id");
        }
        return jobId;
    }
}

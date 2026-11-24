package gg.modl.minecraft.core.imports;

import gg.modl.minecraft.core.imports.support.FileTrees;
import gg.modl.minecraft.core.util.PluginLogger;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

final class ImportFiles {
    private static final String DIRECTORY = "import";
    private static final String COPIES = "copies";
    private static final String NATIVE = "native";
    private static final String MARKER_SUFFIX = ".job";
    private static final String EXPORT_SUFFIX = ".json";
    private static final String OWNER_ONLY_DIRECTORY = "rwx------";
    private static final Pattern JOB_ID = Pattern.compile("[A-Za-z0-9-]{1,36}");

    private final Path directory;
    private final PluginLogger logger;

    ImportFiles(Path dataFolder, PluginLogger logger) {
        this.directory = dataFolder.resolve(DIRECTORY);
        this.logger = logger;
    }

    static Path scratchFolder(Path dataFolder) {
        return dataFolder.resolve(DIRECTORY).resolve(COPIES);
    }

    static Path nativeFolder(Path dataFolder) {
        return dataFolder.resolve(DIRECTORY).resolve(NATIVE);
    }

    void begin(String jobId) throws IOException {
        Path marker = marker(jobId);
        Files.createDirectories(directory);
        if (Files.getFileStore(directory).supportsFileAttributeView(PosixFileAttributeView.class)) {
            Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString(OWNER_ONLY_DIRECTORY));
        }
        Files.write(marker, new byte[0]);
    }

    Path export(String jobId) {
        return directory.resolve(requireJobId(jobId) + EXPORT_SUFFIX);
    }

    void discardExport(String jobId) {
        delete(export(jobId));
    }

    void forget(String jobId) {
        delete(export(jobId));
        delete(marker(jobId));
    }

    List<String> discardExportsAndListInterruptedJobs() {
        List<String> jobs = new ArrayList<>();
        FileTrees.deleteQuietly(directory.resolve(COPIES));
        if (!Files.isDirectory(directory)) return jobs;
        try (DirectoryStream<Path> files = Files.newDirectoryStream(directory)) {
            for (Path file : files) {
                String name = file.getFileName().toString();
                if (name.endsWith(EXPORT_SUFFIX)) {
                    delete(file);
                } else if (name.endsWith(MARKER_SUFFIX)) {
                    String jobId = name.substring(0, name.length() - MARKER_SUFFIX.length());
                    if (JOB_ID.matcher(jobId).matches()) jobs.add(jobId);
                }
            }
        } catch (IOException failure) {
            logger.warning("Could not read the import folder " + directory + ": " + failure);
        }
        return jobs;
    }

    private Path marker(String jobId) {
        return directory.resolve(requireJobId(jobId) + MARKER_SUFFIX);
    }

    private void delete(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException failure) {
            logger.warning("Could not delete the import file " + file + ": " + failure);
        }
    }

    private static String requireJobId(String jobId) {
        if (jobId == null || !JOB_ID.matcher(jobId).matches()) {
            throw new ImportFailure(ImportEnding.EXPORT_FAILED, "import.file_failed", "Invalid import job id");
        }
        return jobId;
    }
}

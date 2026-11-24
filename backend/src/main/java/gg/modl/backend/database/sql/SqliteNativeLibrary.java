package gg.modl.backend.database.sql;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import lombok.extern.slf4j.Slf4j;
import org.sqlite.SQLiteJDBCLoader;

@Slf4j
public final class SqliteNativeLibrary {
    static final String TEMP_DIRECTORY_PROPERTY = "org.sqlite.tmpdir";

    private static final Pattern JVM_FOLDER = Pattern.compile("jvm-(\\d+)-(\\d+)");
    private static final Duration START_TOLERANCE = Duration.ofSeconds(1);

    private SqliteNativeLibrary() {
    }

    public static Path initialize(Path parent) {
        Path folder = parent.resolve(folderName(ProcessHandle.current().pid(), currentStart()));
        createDirectories(folder);
        removeFoldersOfDeadProcesses(parent, folder);
        load(folder);
        return folder;
    }

    static String folderName(long pid, Instant start) {
        return "jvm-" + pid + "-" + start.toEpochMilli();
    }

    static void removeFoldersOfDeadProcesses(Path parent, Path current) {
        for (Path sibling : siblings(parent)) {
            if (!sibling.equals(current) && belongsToDeadProcess(sibling)) {
                deleteQuietly(sibling);
            }
        }
    }

    private static void load(Path folder) {
        String previous = System.getProperty(TEMP_DIRECTORY_PROPERTY);
        System.setProperty(TEMP_DIRECTORY_PROPERTY, folder.toString());
        try {
            if (!SQLiteJDBCLoader.initialize()) {
                throw new IllegalStateException("The SQLite native library could not be loaded");
            }
        } catch (Exception | LinkageError e) {
            throw loadFailure(folder, e);
        } finally {
            if (previous == null) {
                System.clearProperty(TEMP_DIRECTORY_PROPERTY);
            } else {
                System.setProperty(TEMP_DIRECTORY_PROPERTY, previous);
            }
        }
    }

    private static IllegalStateException loadFailure(Path folder, Throwable failure) {
        boolean extractedFileRefused = Stream.iterate(failure, cause -> cause != null, Throwable::getCause)
            .anyMatch(cause -> cause instanceof UnsatisfiedLinkError && String.valueOf(cause.getMessage()).contains(folder.toString()));
        if (extractedFileRefused) {
            return new IllegalStateException("The data directory does not allow executing native libraries; set "
                + "MODL_SQLITE_NATIVE_DIR to a local directory that does, or use an external database", failure);
        }
        return new IllegalStateException("The SQLite native library could not be loaded from " + folder + ": "
            + failure.getMessage(), failure);
    }

    private static Instant currentStart() {
        return ProcessHandle.current().info().startInstant()
            .orElseGet(() -> Instant.ofEpochMilli(ManagementFactory.getRuntimeMXBean().getStartTime()));
    }

    private static boolean belongsToDeadProcess(Path folder) {
        Matcher matcher = JVM_FOLDER.matcher(folder.getFileName().toString());
        if (!matcher.matches()) {
            return false;
        }
        long pid = Long.parseLong(matcher.group(1));
        Instant start = Instant.ofEpochMilli(Long.parseLong(matcher.group(2)));
        Optional<ProcessHandle> process = ProcessHandle.of(pid);
        if (process.isEmpty() || !process.get().isAlive()) {
            return true;
        }
        return process.get().info().startInstant()
            .map(actualStart -> Duration.between(actualStart, start).abs().compareTo(START_TOLERANCE) > 0)
            .orElse(false);
    }

    private static List<Path> siblings(Path parent) {
        try (Stream<Path> entries = Files.list(parent)) {
            return entries.filter(Files::isDirectory).toList();
        } catch (IOException e) {
            log.debug("Could not list {}", parent, e);
            return List.of();
        }
    }

    private static void deleteQuietly(Path folder) {
        try (Stream<Path> tree = Files.walk(folder)) {
            for (Path path : tree.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        } catch (IOException | UncheckedIOException e) {
            log.debug("Could not delete the native library folder {}", folder, e);
        }
    }

    private static void createDirectories(Path folder) {
        try {
            Files.createDirectories(folder);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not create the SQLite native library folder " + folder, e);
        }
    }
}

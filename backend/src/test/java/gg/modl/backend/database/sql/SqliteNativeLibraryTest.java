package gg.modl.backend.database.sql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.InvocationTargetException;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SqliteNativeLibraryTest {
    private static final String NATIVE_LIBRARY_MARKER = "sqlitejdbc";

    @TempDir
    Path nativeParent;

    private String previousTemporaryDirectory;

    @BeforeEach
    void rememberTemporaryDirectory() {
        previousTemporaryDirectory = System.getProperty(SqliteNativeLibrary.TEMP_DIRECTORY_PROPERTY);
    }

    @AfterEach
    void restoreTemporaryDirectory() {
        if (previousTemporaryDirectory == null) {
            System.clearProperty(SqliteNativeLibrary.TEMP_DIRECTORY_PROPERTY);
        } else {
            System.setProperty(SqliteNativeLibrary.TEMP_DIRECTORY_PROPERTY, previousTemporaryDirectory);
        }
    }

    @Test
    void initializationLeavesAnUnsetTemporaryDirectoryPropertyUnset() throws Exception {
        System.clearProperty(SqliteNativeLibrary.TEMP_DIRECTORY_PROPERTY);

        initializeInFreshLoader();

        assertNull(System.getProperty(SqliteNativeLibrary.TEMP_DIRECTORY_PROPERTY));
    }

    @Test
    void initializationRestoresAPreviouslySetTemporaryDirectoryProperty() throws Exception {
        String serverOwnValue = nativeParent.resolve("server-own").toString();
        System.setProperty(SqliteNativeLibrary.TEMP_DIRECTORY_PROPERTY, serverOwnValue);

        initializeInFreshLoader();

        assertEquals(serverOwnValue, System.getProperty(SqliteNativeLibrary.TEMP_DIRECTORY_PROPERTY));
        assertFalse(Files.exists(Path.of(serverOwnValue)));
    }

    @Test
    void theLibraryIsExtractedIntoThisJvmsFolder() throws Exception {
        Path folder = initializeInFreshLoader();

        assertEquals(nativeParent.resolve(SqliteNativeLibrary.folderName(ProcessHandle.current().pid(), currentStart())), folder);
        assertTrue(fileNames(folder).stream().anyMatch(name -> name.contains(NATIVE_LIBRARY_MARKER)), fileNames(folder).toString());
        assertTrue(fileNames(nativeParent).stream().allMatch(name -> name.equals(folder.getFileName().toString())));
    }

    @Test
    void foldersOfDeadProcessesAreRemovedWhileTheCurrentAndLiveProcessesFoldersAreKept() throws Exception {
        long deadPid = pidOfFinishedProcess();
        Instant currentStart = currentStart();
        Path current = folder(ProcessHandle.current().pid(), currentStart);
        Path reusedPid = folder(ProcessHandle.current().pid(), currentStart.minus(Duration.ofSeconds(5)));
        Path dead = folder(deadPid, currentStart);
        Files.writeString(dead.resolve("leftover-library"), "native");
        Path unrelated = Files.createDirectories(nativeParent.resolve("unrelated"));
        try (ChildJvm live = ChildJvm.start(InstanceHolderProcess.class, nativeParent.resolve("live-data").toString())) {
            live.awaitLineStartingWith(InstanceHolderProcess.READY);
            Path liveFolder = folder(live.handle().pid(), live.handle().info().startInstant().orElseThrow());

            SqliteNativeLibrary.removeFoldersOfDeadProcesses(nativeParent, current);

            assertTrue(Files.isDirectory(current));
            assertTrue(Files.isDirectory(liveFolder));
            assertTrue(Files.isDirectory(unrelated));
            assertFalse(Files.exists(reusedPid));
            assertFalse(Files.exists(dead));
        }
    }

    private Path initializeInFreshLoader() throws Exception {
        try (URLClassLoader loader = IsolatedLoaders.create()) {
            return IsolatedLoaders.call(loader, () -> initialize(loader));
        }
    }

    private Path initialize(ClassLoader loader) throws Exception {
        try {
            return (Path) Class.forName(SqliteNativeLibrary.class.getName(), true, loader)
                .getMethod("initialize", Path.class)
                .invoke(null, nativeParent);
        } catch (InvocationTargetException e) {
            throw new IllegalStateException(CauseChain.messages(e.getCause()), e.getCause());
        }
    }

    private Path folder(long pid, Instant start) throws IOException {
        return Files.createDirectories(nativeParent.resolve(SqliteNativeLibrary.folderName(pid, start)));
    }

    private static Instant currentStart() {
        return ProcessHandle.current().info().startInstant().orElseThrow();
    }

    private static long pidOfFinishedProcess() throws IOException, InterruptedException {
        Process process = new ProcessBuilder(ChildJvm.javaExecutable(), "-version").redirectErrorStream(true).start();
        process.getInputStream().transferTo(OutputStream.nullOutputStream());
        process.waitFor();
        long pid = process.pid();
        assumeTrue(ProcessHandle.of(pid).filter(ProcessHandle::isAlive).isEmpty(), "the finished process id was reused");
        return pid;
    }

    private static List<String> fileNames(Path folder) throws IOException {
        try (Stream<Path> entries = Files.list(folder)) {
            return entries.map(path -> path.getFileName().toString()).toList();
        }
    }
}

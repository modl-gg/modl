package gg.modl.minecraft.features.staffmode;

import gg.modl.minecraft.core.util.PluginLogger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StartingLocationFileTest {
    @TempDir
    Path dataFolder;

    private final RecordingLogger logger = new RecordingLogger();
    private final UUID staff = UUID.randomUUID();
    private final PendingReturn pending = new PendingReturn(
            new StartingLocation(UUID.randomUUID(), -1234.0625, 64.5, 2.9E7, -179.99f, 45.5f), 1_700_000_000_000L);

    @Test
    void aSavedReturnSurvivesANewInstance() {
        Map<UUID, PendingReturn> returns = new LinkedHashMap<>();
        returns.put(staff, pending);
        returns.put(UUID.randomUUID(), pending);

        saveAndClose(returns);

        assertEquals(returns, loadSaved());
        assertFalse(Files.exists(temporaryFile()));
        assertTrue(logger.warnings.isEmpty());
    }

    @Test
    void closingWritesTheNewestOfManySaves() {
        StartingLocationFile file = newFile();
        for (int i = 0; i < 200; i++) file.save(Collections.singletonMap(new UUID(0, i), pending));

        file.close();

        assertEquals(Collections.singletonMap(new UUID(0, 199), pending), loadSaved());
    }

    @Test
    void theFileIsDeletedOnceNothingIsPending() {
        saveAndClose(Collections.singletonMap(staff, pending));
        assertTrue(Files.exists(file()));

        saveAndClose(Collections.emptyMap());

        assertFalse(Files.exists(file()));
    }

    @Test
    void aSaveAfterCloseIsIgnored() {
        StartingLocationFile file = newFile();
        file.close();

        file.save(Collections.singletonMap(staff, pending));

        assertFalse(Files.exists(file()));
    }

    @Test
    void aMissingFileMeansNothingPendingAndNoWarning() {
        assertTrue(loadSaved().isEmpty());
        assertTrue(logger.warnings.isEmpty());
    }

    @Test
    void unreadableLinesAreSkippedAndTheRestIsKept() throws IOException {
        String world = pending.getLocation().getWorldId().toString();
        Files.write(file(), Arrays.asList("modl-staff-mode-returns 1",
                staff + " " + world + " -1234.0625 64.5 2.9E7 -179.99 45.5 1700000000000",
                UUID.randomUUID() + " " + world + " NaN 64 1 0 0 1",
                UUID.randomUUID() + " " + world + " 1 Infinity 1 0 0 1",
                "not-a-uuid " + world + " 1 64 1 0 0 1",
                "cut off"), StandardCharsets.UTF_8);

        assertEquals(Collections.singletonMap(staff, pending), loadSaved());
        assertEquals(1, logger.warnings.size());
        assertTrue(logger.warnings.get(0).contains("Skipped 4 unreadable lines"));
    }

    @Test
    void anUnknownFormatIsReportedAndNotLoaded() throws IOException {
        Files.write(file(), Collections.singletonList("something else"), StandardCharsets.UTF_8);

        assertTrue(loadSaved().isEmpty());
        assertEquals(1, logger.warnings.size());
    }

    @Test
    void anOversizedFileIsNotRead() throws IOException {
        Files.write(file(), new byte[(int) StartingLocationFile.MAX_BYTES + 1]);

        assertTrue(loadSaved().isEmpty());
        assertTrue(logger.warnings.get(0).contains("larger than"));
    }

    @Test
    void writingHappensOffTheCallersThreadAndReportsAFailureOnce() throws IOException {
        Files.createDirectory(temporaryFile());
        StartingLocationFile file = newFile();

        file.save(Collections.singletonMap(staff, pending));
        file.save(Collections.singletonMap(UUID.randomUUID(), pending));
        file.close();

        assertEquals(1, logger.warnings.size());
        assertEquals(Collections.singletonList("modl-staff-mode-returns"), logger.threads);
    }

    private void saveAndClose(Map<UUID, PendingReturn> returns) {
        StartingLocationFile file = newFile();
        file.save(returns);
        file.close();
    }

    private Map<UUID, PendingReturn> loadSaved() {
        StartingLocationFile file = newFile();
        Map<UUID, PendingReturn> loaded = file.load().join();
        file.close();
        return loaded;
    }

    private StartingLocationFile newFile() {
        return new StartingLocationFile(dataFolder, logger);
    }

    private Path file() {
        return dataFolder.resolve(StartingLocationFile.FILE_NAME);
    }

    private Path temporaryFile() {
        return dataFolder.resolve(StartingLocationFile.FILE_NAME + ".tmp");
    }

    private static final class RecordingLogger implements PluginLogger {
        private final List<String> warnings = new CopyOnWriteArrayList<>();
        private final List<String> threads = new CopyOnWriteArrayList<>();

        @Override
        public void info(String message) {
        }

        @Override
        public void warning(String message) {
            warnings.add(message);
            threads.add(Thread.currentThread().getName());
        }

        @Override
        public void severe(String message) {
        }
    }
}

package gg.modl.minecraft.core.imports;

import gg.modl.minecraft.core.support.MapLocaleManager;
import gg.modl.minecraft.core.support.RecordingPluginLogger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ImportJobFollowerTest {
    @TempDir
    Path dataFolder;

    private final FakeImportBackend backend = new FakeImportBackend();
    private final List<String> console = new CopyOnWriteArrayList<>();
    private final RecordingPluginLogger logger = new RecordingPluginLogger();

    @Test
    void anImportInterruptedBeforeAcceptanceIsEndedAndCleanedUp() throws IOException {
        backend.withJob(ImportJobs.UPLOADING);
        ImportFiles files = leftBehind();
        Path export = files.export(FakeImportBackend.JOB);

        for (String job : files.discardExportsAndListInterruptedJobs()) follower(files).resume(job);

        assertEquals(1, backend.ends().size());
        assertEquals(ImportEnding.INTERRUPTED.wireName(), backend.ends().get(0).getReason());
        assertEquals(ImportEnding.INTERRUPTED.messageKey(), console.get(console.size() - 1));
        assertFalse(Files.exists(export));
        assertTrue(files.discardExportsAndListInterruptedJobs().isEmpty());
    }

    @Test
    void anImportTheServerFinishedIsOnlyReported() throws IOException {
        backend.withJob(ImportJobs.COMPLETED);
        ImportFiles files = leftBehind();

        for (String job : files.discardExportsAndListInterruptedJobs()) follower(files).resume(job);

        assertTrue(backend.ends().isEmpty());
        assertEquals("import.completed", console.get(console.size() - 1));
        assertTrue(files.discardExportsAndListInterruptedJobs().isEmpty());
    }

    @Test
    void aStopDuringProcessingLeavesTheMarkerForTheNextStart() throws IOException {
        backend.withJob(ImportJobs.PROCESSING);
        ImportFiles files = leftBehind();
        ImportSignal stopping = new ImportSignal();
        stopping.set();

        follower(files, stopping).follow(FakeImportBackend.JOB);

        assertTrue(backend.ends().isEmpty());
        assertTrue(console.isEmpty());
        assertEquals(1, files.discardExportsAndListInterruptedJobs().size());
    }

    private ImportFiles leftBehind() throws IOException {
        ImportFiles files = new ImportFiles(dataFolder, logger);
        files.begin(FakeImportBackend.JOB);
        Files.write(files.export(FakeImportBackend.JOB), new byte[]{'{', '}'});
        return files;
    }

    private ImportJobFollower follower(ImportFiles files) {
        return follower(files, new ImportSignal());
    }

    private ImportJobFollower follower(ImportFiles files, ImportSignal lifecycle) {
        ImportReporter reporter = new ImportReporter(new MapLocaleManager().withFallback(path -> path), console::add,
                new ImportSources(Collections.emptyList()));
        return new ImportJobFollower(new ImportCalls(backend, 0), files, reporter, lifecycle, ImportTimings.DEFAULT);
    }
}

package gg.modl.minecraft.core.imports;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ImportUploaderTest {
    private static final int BLOCKED_BY_PROXY = 413, UNAVAILABLE = 503;

    @TempDir
    Path directory;

    private final FakeImportBackend backend = new FakeImportBackend().withJob(ImportJobs.UPLOADING);
    private final ImportSignal signal = new ImportSignal();
    private Path file;

    @BeforeEach
    void writeExport() throws IOException {
        file = Files.write(directory.resolve("export.json"), new byte[]{'{', '}'});
    }

    @Test
    void anAcceptedFileIsSentOnce() {
        uploader().upload(FakeImportBackend.JOB, file);

        assertEquals(1, backend.uploads().size());
    }

    @Test
    void aRefusalOfAnAlreadyAcceptedFileCountsAsReceived() {
        backend.answering(FakeImportBackend.CONFLICT, ImportJobs.PROCESSING);

        uploader().upload(FakeImportBackend.JOB, file);

        assertEquals(1, backend.uploads().size());
    }

    @Test
    void aSizeLimitInFrontOfModlIsNamed() {
        backend.answering(BLOCKED_BY_PROXY, ImportJobs.UPLOADING);

        ImportFailure failure = assertThrows(ImportFailure.class, () -> uploader().upload(FakeImportBackend.JOB, file));

        assertEquals("import.upload_blocked", failure.getMessageKey());
        assertEquals(1, backend.uploads().size());
    }

    @Test
    void failedTransfersAreRetriedAFewTimes() {
        backend.answering(UNAVAILABLE, ImportJobs.UPLOADING);

        ImportFailure failure = assertThrows(ImportFailure.class, () -> uploader().upload(FakeImportBackend.JOB, file));

        assertEquals("import.upload_failed", failure.getMessageKey());
        assertEquals(ImportUploader.MAX_FAILED_ATTEMPTS, backend.uploads().size());
    }

    @Test
    void anEndedJobIsNotRetried() {
        backend.answering(FakeImportBackend.CONFLICT, ImportJobs.FAILED);

        assertThrows(ImportFailure.class, () -> uploader().upload(FakeImportBackend.JOB, file));

        assertEquals(1, backend.uploads().size());
    }

    @Test
    void aCancelledRunSendsNothing() {
        signal.set();

        assertThrows(ImportFailure.class, () -> uploader().upload(FakeImportBackend.JOB, file));

        assertEquals(0, backend.uploads().size());
    }

    private ImportUploader uploader() {
        ImportTimings timings = ImportTimings.DEFAULT.toBuilder().retryEvery(Duration.ZERO).build();
        return new ImportUploader(backend, new ImportCalls(backend, 0), signal, timings);
    }
}

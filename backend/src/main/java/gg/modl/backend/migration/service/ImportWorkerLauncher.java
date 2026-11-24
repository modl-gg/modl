package gg.modl.backend.migration.service;

import gg.modl.backend.instance.InstanceContext;
import gg.modl.backend.migration.data.ImportEnding;
import gg.modl.backend.migration.data.ImportedCounts;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class ImportWorkerLauncher {
    static final String BUSY_MESSAGE = "Import processing is busy. Run the import again shortly.";

    private final InstanceContext instanceContext;
    private final MigrationProcessor migrationProcessor;
    private final ImportProcessingJobs processingJobs;

    public void launch(String jobId, Path file) {
        try {
            migrationProcessor.processFileAsync(instanceContext.instance(), jobId, file);
        } catch (TaskRejectedException busy) {
            log.warn("The import worker refused job {}", jobId, busy);
            processingJobs.fail(jobId, ImportEnding.IMPORT_FAILED, BUSY_MESSAGE, ImportedCounts.NONE);
            discard(file);
        }
    }

    private static void discard(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            log.warn("Failed to delete import upload {}", file, e);
        }
    }
}

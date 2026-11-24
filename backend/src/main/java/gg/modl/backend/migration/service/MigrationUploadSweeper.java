package gg.modl.backend.migration.service;

import gg.modl.backend.infrastructure.scheduling.ScheduledDatabaseJobs;
import gg.modl.backend.migration.data.MigrationStatus;
import gg.modl.backend.migration.repository.ImportJobRepository;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@Slf4j
public class MigrationUploadSweeper {
    private final ImportUploadFiles files;
    private final ImportJobRepository importJobRepository;
    private final ScheduledDatabaseJobs databaseJobs;
    private final Clock clock;
    private final Duration staleness;

    public MigrationUploadSweeper(
        ImportUploadFiles files,
        ImportJobRepository importJobRepository,
        ScheduledDatabaseJobs databaseJobs,
        Clock clock,
        @Value("${modl.migration.sweep.staleness:PT30M}") Duration staleness
    ) {
        this.files = files;
        this.importJobRepository = importJobRepository;
        this.databaseJobs = databaseJobs;
        this.clock = clock;
        this.staleness = staleness;
    }

    @Scheduled(fixedDelayString = "${modl.migration.sweep.interval:PT15M}")
    public void sweepStaleUploads() {
        databaseJobs.run("Removing stale migration uploads", this::deleteStaleUploads);
    }

    void deleteStaleUploads() {
        Instant cutoff = clock.instant().minus(staleness);
        int deleted = 0;
        try {
            List<Path> uploads = files.uploads();
            Optional<String> activeJobId = importJobRepository.findActive().map(MigrationStatus::getId);
            for (Path upload : uploads) {
                boolean inUse = activeJobId.filter(jobId -> files.belongsTo(upload, jobId)).isPresent();
                if (!inUse && lastChangedBefore(upload, cutoff)) {
                    deleted += deleteQuietly(upload);
                }
            }
        } catch (IOException e) {
            log.warn("Failed to sweep stale migration uploads", e);
        }

        if (deleted > 0) {
            log.info("Swept {} stale migration upload(s)", deleted);
        }
    }

    private boolean lastChangedBefore(Path file, Instant cutoff) {
        try {
            return Files.getLastModifiedTime(file).toInstant().isBefore(cutoff);
        } catch (IOException e) {
            log.warn("Failed to read modification time of migration upload {}", file, e);
            return false;
        }
    }

    private int deleteQuietly(Path file) {
        try {
            return Files.deleteIfExists(file) ? 1 : 0;
        } catch (IOException e) {
            log.warn("Failed to delete stale migration upload {}", file, e);
            return 0;
        }
    }
}

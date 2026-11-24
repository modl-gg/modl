package gg.modl.backend.migration.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import gg.modl.backend.infrastructure.scheduling.ScheduledDatabaseJobs;
import gg.modl.backend.migration.data.ImportState;
import gg.modl.backend.migration.data.MigrationStatus;
import gg.modl.backend.migration.repository.ImportJobRepository;
import gg.modl.backend.support.MutableClock;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MigrationUploadSweeperTest {
    private static final Instant NOW = Instant.parse("2026-01-01T12:00:00Z");
    private static final Instant LONG_AGO = NOW.minus(Duration.ofHours(3));

    @TempDir
    Path directory;

    private final ImportJobRepository jobs = mock(ImportJobRepository.class);

    @Test
    void staleUploadsGoButNeverThoseOfTheActiveJob() throws IOException {
        when(jobs.findActive()).thenReturn(Optional.of(
            MigrationStatus.builder().id("active").status(ImportState.PROCESSING).build()));
        Path accepted = file("migration-active.json", LONG_AGO);
        Path resumed = file("migration-active.resumed", LONG_AGO);
        Path partial = file("migration-active-123.part", LONG_AGO);
        Path unrelated = file("notes.txt", LONG_AGO);
        Path fresh = file("migration-ended.json", NOW);
        Path staleFile = file("migration-other.json", LONG_AGO);
        Path stalePart = file("migration-other-456.part", LONG_AGO);
        Path longerId = file("migration-active2.json", LONG_AGO);

        sweeper(directory).deleteStaleUploads();

        assertThat(accepted).exists();
        assertThat(resumed).exists();
        assertThat(partial).exists();
        assertThat(unrelated).exists();
        assertThat(fresh).exists();
        assertThat(staleFile).doesNotExist();
        assertThat(stalePart).doesNotExist();
        assertThat(longerId).doesNotExist();
    }

    @Test
    void aMissingDirectoryIsLeftMissing() {
        Path missing = directory.resolve("missing");

        sweeper(missing).deleteStaleUploads();

        assertThat(missing).doesNotExist();
    }

    private MigrationUploadSweeper sweeper(Path uploads) {
        return new MigrationUploadSweeper(new ImportUploadFiles(uploads), jobs, mock(ScheduledDatabaseJobs.class),
            new MutableClock(NOW), Duration.ofMinutes(30));
    }

    private Path file(String name, Instant modified) throws IOException {
        Path file = Files.writeString(directory.resolve(name), "{}");
        Files.setLastModifiedTime(file, FileTime.from(modified));
        return file;
    }
}

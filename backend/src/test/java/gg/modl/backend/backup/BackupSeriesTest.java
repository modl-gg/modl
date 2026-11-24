package gg.modl.backend.backup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import gg.modl.backend.backup.BackupSeries.WrittenBackup;
import gg.modl.backend.infrastructure.disk.DiskSpaceFloor;
import gg.modl.backend.infrastructure.disk.FakeFileStore;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BackupSeriesTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-21T04:00:00Z"), ZoneOffset.UTC);
    private static final String PREFIX = "modl-backup-";
    private static final long MARGIN = 64L * 1024 * 1024;
    private static final long FLOOR = 1_000_000L;
    private static final long ESTIMATE = 10_000L;
    private static final long ARCHIVE_BYTES = 100L;

    @TempDir
    Path directory;

    @Test
    void keepsOnlyTheConfiguredNumberOfBackups() throws IOException {
        seed("20261101", "20261102", "20261103", "20261104", "20261105");

        series(plentyOfSpace(), 3).write(ESTIMATE, BackupSeriesTest::writeArchive);

        assertThat(archiveNames()).containsExactly(
            "modl-backup-20261104-040000.zip", "modl-backup-20261105-040000.zip", "modl-backup-20261121-040000.zip");
    }

    @Test
    void removesTheOldestBackupsUntilTheNewOneFitsAboveTheFloor() throws IOException {
        seed("20261101", "20261102", "20261103");

        WrittenBackup backup = series(shortBy(150), 7).write(ESTIMATE, BackupSeriesTest::writeArchive);

        assertThat(backup.removedToMakeRoom()).extracting(path -> path.getFileName().toString())
            .containsExactly("modl-backup-20261101-040000.zip", "modl-backup-20261102-040000.zip");
        assertThat(archiveNames()).containsExactly("modl-backup-20261103-040000.zip", "modl-backup-20261121-040000.zip");
    }

    @Test
    void removesNothingWhenOlderBackupsCannotFreeEnoughSpace() throws IOException {
        seed("20261101", "20261102", "20261103");
        BackupSeries series = series(shortBy(250), 7);

        assertThatThrownBy(() -> series.write(ESTIMATE, BackupSeriesTest::writeArchive))
            .isInstanceOf(IOException.class)
            .hasMessageContaining("not enough free disk space");
        assertThat(archiveNames()).containsExactly(
            "modl-backup-20261101-040000.zip", "modl-backup-20261102-040000.zip", "modl-backup-20261103-040000.zip");
    }

    @Test
    void alwaysKeepsTheNewestBackup() throws IOException {
        seed("20261101");
        BackupSeries series = series(shortBy(1), 7);

        assertThatThrownBy(() -> series.write(ESTIMATE, BackupSeriesTest::writeArchive)).isInstanceOf(IOException.class);
        assertThat(archiveNames()).containsExactly("modl-backup-20261101-040000.zip");
    }

    @Test
    void aBackupNeverPushesFreeSpaceBelowTheFloor() throws IOException {
        long exactlyEnough = FLOOR + ESTIMATE + MARGIN;

        assertThatThrownBy(() -> series(withUsable(exactlyEnough - 1), 7).write(ESTIMATE, BackupSeriesTest::writeArchive))
            .isInstanceOf(IOException.class);
        assertThat(archiveNames()).isEmpty();

        series(withUsable(exactlyEnough), 7).write(ESTIMATE, BackupSeriesTest::writeArchive);
        assertThat(archiveNames()).containsExactly("modl-backup-20261121-040000.zip");
    }

    @Test
    void filesThatMerelyShareThePrefixAreNotPartOfTheSeries() throws IOException {
        seed("20261101", "20261102");
        Files.writeString(directory.resolve("modl-backup-before-upgrade.zip"), "kept by the operator");

        series(plentyOfSpace(), 1).write(ESTIMATE, BackupSeriesTest::writeArchive);

        assertThat(archiveNames()).containsExactly("modl-backup-20261121-040000.zip", "modl-backup-before-upgrade.zip");
    }

    @Test
    void otherSeriesInTheSameDirectoryAreLeftAlone() throws IOException {
        Files.createDirectories(directory);
        Files.writeString(directory.resolve("pre-restore-20261101-040000.zip"), "x".repeat((int) ARCHIVE_BYTES));
        seed("20261101", "20261102");

        series(shortBy(50), 7).write(ESTIMATE, BackupSeriesTest::writeArchive);

        assertThat(archiveNames()).containsExactly(
            "modl-backup-20261102-040000.zip", "modl-backup-20261121-040000.zip", "pre-restore-20261101-040000.zip");
    }

    private BackupSeries series(BackupDiskSpace diskSpace, int retained) {
        return new BackupSeries(new BackupFiles(directory, CLOCK), diskSpace, PREFIX, retained);
    }

    private BackupDiskSpace plentyOfSpace() {
        return withUsable(Long.MAX_VALUE / 4);
    }

    private BackupDiskSpace shortBy(long bytes) {
        return withUsable(FLOOR + ESTIMATE + MARGIN - bytes);
    }

    private BackupDiskSpace withUsable(long usableBytesNow) {
        long occupiedNow = occupiedBytes();
        return new BackupDiskSpace(new DiskSpaceFloor(FLOOR, 0),
            path -> new FakeFileStore(Long.MAX_VALUE, usableBytesNow + occupiedNow - occupiedBytes()));
    }

    private long occupiedBytes() {
        if (!Files.isDirectory(directory)) {
            return 0;
        }
        try (Stream<Path> files = Files.list(directory)) {
            return files.mapToLong(path -> path.toFile().length()).sum();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void seed(String... days) throws IOException {
        Files.createDirectories(directory);
        for (String day : days) {
            Files.writeString(directory.resolve(PREFIX + day + "-040000.zip"), "x".repeat((int) ARCHIVE_BYTES));
        }
    }

    private List<String> archiveNames() throws IOException {
        try (Stream<Path> files = Files.list(directory)) {
            return files.map(path -> path.getFileName().toString()).sorted().toList();
        }
    }

    private static void writeArchive(OutputStream output) throws IOException {
        output.write("backup".getBytes(StandardCharsets.UTF_8));
    }
}

package gg.modl.backend.database.backup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import gg.modl.backend.backup.BackupPolicy;
import gg.modl.backend.database.DatabaseDialect;
import gg.modl.backend.database.sql.DatabaseWorkExecutor;
import gg.modl.backend.database.sql.OfflineDatabase;
import gg.modl.backend.infrastructure.logging.OperatorNotice;
import gg.modl.backend.infrastructure.scheduling.ScheduledDatabaseJobs;
import gg.modl.backend.support.sql.TestDialects;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.stream.Stream;
import java.util.zip.ZipFile;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class NightlyBackupsTest {
    private static final BackupPolicy DEFAULT_POLICY = BackupPolicy.of(7, 4);

    @TempDir
    Path directory;

    private BackupScenario scenario;
    private OfflineDatabase database;
    private DatabaseWorkExecutor work;
    private OperatorNotice operatorNotice;

    @BeforeEach
    void setUp() {
        scenario = BackupScenario.fresh(TestDialects.SQLITE, directory);
        scenario.seeded();
        database = OfflineDatabase.open(scenario.database(), scenario.dataDirectory());
        work = new DatabaseWorkExecutor(1, DatabaseWorkExecutor.JOB_THREADS);
        operatorNotice = mock(OperatorNotice.class);
    }

    @AfterEach
    void tearDown() {
        work.close();
        database.close();
    }

    @Test
    void nightlyBackupsAreSnapshotsAndKeepTheLastSeven() throws IOException {
        Path backups = Files.createDirectories(scenario.dataDirectory().backups());
        Path abandoned = Files.writeString(backups.resolve(".modl-backup-123.partial"), "abandoned");
        Files.setLastModifiedTime(abandoned, FileTime.from(Instant.now().minus(Duration.ofHours(2))));
        for (int day = 1; day <= 8; day++) {
            Files.writeString(backups.resolve("modl-backup-202611%02d-040000.zip".formatted(day)), "old");
        }

        nightlyBackups(DatabaseDialect.SQLITE, DEFAULT_POLICY).backUp();

        try (Stream<Path> files = Files.list(backups)) {
            assertThat(files.toList()).hasSize(7).contains(backups.resolve("modl-backup-20261121-040000.zip"));
        }
        try (ZipFile snapshot = new ZipFile(backups.resolve("modl-backup-20261121-040000.zip").toFile())) {
            assertThat(snapshot.getEntry(ArchiveManifest.ENTRY_NAME)).isNotNull();
            assertThat(snapshot.getEntry(SnapshotArchiveSource.DATABASE_ENTRY)).isNotNull();
        }
        verify(operatorNotice, never()).publish(any(), any(Object[].class));
    }

    @Test
    void aBackupThatIsStillBeingWrittenByAnotherProcessIsNotRemoved() throws IOException {
        Path backups = Files.createDirectories(scenario.dataDirectory().backups());
        Path inProgress = Files.writeString(backups.resolve(".modl-backup-456.partial"), "still written");

        nightlyBackups(DatabaseDialect.SQLITE, DEFAULT_POLICY).backUp();

        assertThat(inProgress).exists();
    }

    @Test
    void aFailedNightlyBackupIsReportedToTheOperator() throws IOException {
        Files.writeString(scenario.dataDirectory().backups(), "not a directory");

        nightlyBackups(DatabaseDialect.SQLITE, DEFAULT_POLICY).backUp();

        verify(operatorNotice).publish(startsWith("The nightly database backup to"), any(Object[].class));
    }

    @Test
    void theNumberOfNightlyBackupsKeptIsConfigurable() throws IOException {
        Path backups = Files.createDirectories(scenario.dataDirectory().backups());
        for (int day = 1; day <= 5; day++) {
            Files.writeString(backups.resolve("modl-backup-202611%02d-040000.zip".formatted(day)), "old");
        }

        nightlyBackups(DatabaseDialect.SQLITE, BackupPolicy.of(3, 4)).backUp();

        try (Stream<Path> files = Files.list(backups)) {
            assertThat(files.map(path -> path.getFileName().toString()).sorted().toList()).containsExactly(
                "modl-backup-20261104-040000.zip", "modl-backup-20261105-040000.zip", "modl-backup-20261121-040000.zip");
        }
    }

    @Test
    void externalDatabasesGetNoNightlyBackup() {
        for (DatabaseDialect dialect : DatabaseDialect.values()) {
            if (dialect.isExternal()) {
                nightlyBackups(dialect, DEFAULT_POLICY).backUp();
            }
        }

        assertThat(scenario.dataDirectory().backups()).doesNotExist();
        verifyNoInteractions(operatorNotice);
    }

    @Test
    void backupPoliciesNeedAPositiveCountAndAnHourOfTheDay() {
        assertThat(BackupPolicy.of(30, 23).keep()).isEqualTo(30);
        assertThatThrownBy(() -> BackupPolicy.of(0, 4)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> BackupPolicy.of(7, 24)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> BackupPolicy.of(7, -1)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theHourlyTickBacksUpOnlyAtTheConfiguredLocalHour() throws IOException {
        int hourNow = ZonedDateTime.now(BackupScenario.CLOCK.withZone(ZoneId.systemDefault())).getHour();

        nightlyBackups(DatabaseDialect.SQLITE, BackupPolicy.of(7, (hourNow + 1) % 24)).backUpWhenScheduled();
        assertThat(scenario.dataDirectory().backups()).doesNotExist();

        nightlyBackups(DatabaseDialect.SQLITE, BackupPolicy.of(7, hourNow)).backUpWhenScheduled();
        assertThat(nightlyBackups(DatabaseDialect.SQLITE, DEFAULT_POLICY).archives()).hasSize(1);
    }

    @Test
    void aChangedCountAppliesToTheNextBackupWithoutARestart() throws IOException {
        Path backups = Files.createDirectories(scenario.dataDirectory().backups());
        for (int day = 1; day <= 5; day++) {
            Files.writeString(backups.resolve("modl-backup-202611%02d-040000.zip".formatted(day)), "old");
        }
        MutablePolicy policy = new MutablePolicy(7);
        NightlyBackups nightly = nightlyBackups(DatabaseDialect.SQLITE, policy);

        policy.keep = 2;
        nightly.backUp();

        assertThat(nightly.archives()).hasSize(2);
    }

    private NightlyBackups nightlyBackups(DatabaseDialect dialect, BackupPolicy properties) {
        DatabaseBackup backup = new ArchivingDatabaseBackup(database.source(), scenario.dataDirectory().temporary(),
            BackupScenario.UNLIMITED, BackupScenario.CLOCK);
        return new NightlyBackups(backup, dialect, new ScheduledDatabaseJobs(work, () -> true), operatorNotice, scenario.dataDirectory(), properties, BackupScenario.CLOCK);
    }

    private static final class MutablePolicy implements BackupPolicy {
        private int keep;

        private MutablePolicy(int keep) {
            this.keep = keep;
        }

        @Override
        public int keep() {
            return keep;
        }

        @Override
        public int hour() {
            return 4;
        }
    }
}

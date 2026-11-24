package gg.modl.backend.database.backup;

import gg.modl.backend.backup.BackupDiskSpace;
import gg.modl.backend.backup.BackupFiles;
import gg.modl.backend.backup.BackupPolicy;
import gg.modl.backend.backup.BackupSeries;
import gg.modl.backend.backup.BackupSeries.WrittenBackup;
import gg.modl.backend.database.DataDirectoryProperties;
import gg.modl.backend.database.DatabaseDialect;
import gg.modl.backend.database.DatabaseUnavailableException;
import gg.modl.backend.infrastructure.logging.OperatorNotice;
import gg.modl.backend.infrastructure.scheduling.ScheduledDatabaseJobs;
import gg.modl.backend.infrastructure.util.FailureDescriptions;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@Slf4j
public class NightlyBackups {
    private static final String NIGHTLY_PREFIX = "modl-backup-";

    private final DatabaseBackup databaseBackup;
    private final DatabaseDialect dialect;
    private final ScheduledDatabaseJobs jobs;
    private final OperatorNotice operatorNotice;
    private final BackupFiles files;
    private final BackupPolicy policy;
    private final Clock clock;

    public NightlyBackups(
        DatabaseBackup databaseBackup,
        DatabaseDialect dialect,
        ScheduledDatabaseJobs jobs,
        OperatorNotice operatorNotice,
        DataDirectoryProperties dataDirectory,
        BackupPolicy policy,
        Clock clock
    ) {
        this.databaseBackup = databaseBackup;
        this.dialect = dialect;
        this.jobs = jobs;
        this.operatorNotice = operatorNotice;
        this.files = new BackupFiles(dataDirectory.backups(), clock);
        this.policy = policy;
        this.clock = clock;
    }

    public List<Path> archives() throws IOException {
        return files.archives(NIGHTLY_PREFIX);
    }

    @Scheduled(cron = "0 0 * * * *")
    public void backUpWhenScheduled() {
        if (ZonedDateTime.now(clock.withZone(ZoneId.systemDefault())).getHour() == policy.hour()) {
            backUp();
        }
    }

    public void backUp() {
        if (dialect.isExternal()) {
            return;
        }
        jobs.run("The nightly database backup", this::writeBackup);
    }

    private void writeBackup() {
        BackupSeries series = new BackupSeries(files, BackupDiskSpace.SYSTEM, NIGHTLY_PREFIX, policy.keep());
        try {
            WrittenBackup backup = databaseBackup.writeInto(series, ArchiveKind.SNAPSHOT);
            if (!backup.removedToMakeRoom().isEmpty()) {
                operatorNotice.publish("Disk space is low: removed {} older nightly backup(s) in {} to make room for the new one",
                    backup.removedToMakeRoom().size(), series.directory());
            }
            log.info("Wrote nightly database backup {} ({} bytes)", backup.archive(), Files.size(backup.archive()));
        } catch (DatabaseUnavailableException e) {
            log.debug("Stopped the nightly database backup because the database became unavailable", e);
        } catch (IOException | RuntimeException e) {
            log.error("Nightly database backup to {} failed", series.directory(), e);
            operatorNotice.publish("The nightly database backup to {} failed: {}", series.directory(),
                FailureDescriptions.describe(e));
        }
    }
}

package gg.modl.backend.database.backup;

import gg.modl.backend.backup.BackupDiskSpace;
import gg.modl.backend.database.sql.RestoreTarget;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.zip.ZipOutputStream;

public final class DatabaseCopy {
    private static final String WORK_PREFIX = "modl-copy-";
    private static final String COPY_FILE = "copy.zip";

    private final DatabaseBackup backup;
    private final Path temporary;
    private final BackupDiskSpace diskSpace;
    private final Clock clock;

    public DatabaseCopy(DatabaseBackup backup, Path temporary, BackupDiskSpace diskSpace, Clock clock) {
        this.backup = backup;
        this.temporary = temporary;
        this.diskSpace = diskSpace;
        this.clock = clock;
    }

    public TransferReport into(RestoreTarget target) throws IOException {
        try (WorkDirectory work = WorkDirectory.createReplacingAbandoned(temporary, WORK_PREFIX, clock.instant())) {
            Path archive = work.path().resolve(COPY_FILE);
            diskSpace.require(work.path(), backup.requiredBytes(ArchiveKind.LOGICAL));
            try (ZipOutputStream zip = new ZipOutputStream(new BufferedOutputStream(Files.newOutputStream(archive)))) {
                backup.writeMoveCopy(zip);
            }
            DatabaseRestore restore = new DatabaseRestore(target, diskSpace);
            TransferReport report;
            try (RestorableArchive verified = restore.open(archive)) {
                report = verified.transfer();
            }
            restore.upgradeSchema(clock.instant());
            return report;
        }
    }
}

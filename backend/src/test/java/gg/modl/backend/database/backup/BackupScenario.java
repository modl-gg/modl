package gg.modl.backend.database.backup;

import gg.modl.backend.backup.BackupDiskSpace;
import gg.modl.backend.backup.BackupFiles;
import gg.modl.backend.database.DataDirectoryProperties;
import gg.modl.backend.database.WriteAttemptHook;
import gg.modl.backend.database.sql.LockedDatabase;
import gg.modl.backend.database.sql.OfflineDatabase;
import gg.modl.backend.database.sql.SqlDatabaseProperties;
import gg.modl.backend.infrastructure.disk.DiskSpaceFloor;
import gg.modl.backend.support.sql.DialectTarget;
import gg.modl.backend.support.sql.SqlTestDatabase;
import gg.modl.backend.support.sql.TestDialects;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipOutputStream;

record BackupScenario(DialectTarget target, SqlDatabaseProperties database, DataDirectoryProperties dataDirectory) {
    static final BackupDiskSpace UNLIMITED = new BackupDiskSpace(new DiskSpaceFloor(0, 0), Files::getFileStore);
    static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-21T04:00:00Z"), ZoneOffset.UTC);

    static BackupScenario fresh(DialectTarget target, Path directory) {
        return new BackupScenario(target, TestDialects.freshDatabase(target), new DataDirectoryProperties(directory.toString()));
    }

    SqlTestDatabase start() {
        return SqlTestDatabase.start(target, dataDirectory.root(), database, WriteAttemptHook.NONE);
    }

    Map<String, List<String>> contents() {
        try (SqlTestDatabase running = start()) {
            return BackupTestData.contents(running);
        }
    }

    Map<String, List<String>> seeded() {
        try (SqlTestDatabase running = start()) {
            BackupTestData.seed(running);
            return BackupTestData.contents(running);
        }
    }

    TransferReport write(ArchiveKind kind, Path archive) throws IOException {
        try (OfflineDatabase offline = OfflineDatabase.open(database, dataDirectory)) {
            DatabaseBackup backup = new ArchivingDatabaseBackup(offline.source(), dataDirectory.temporary(), UNLIMITED, CLOCK);
            TransferReport[] report = new TransferReport[1];
            BackupFiles.writeAtomically(archive, output -> {
                try (ZipOutputStream zip = new ZipOutputStream(output)) {
                    report[0] = backup.write(kind, zip);
                }
            });
            return report[0];
        }
    }

    void writeMoveCopy(Path archive) throws IOException {
        try (OfflineDatabase offline = OfflineDatabase.open(database, dataDirectory)) {
            DatabaseBackup backup = new ArchivingDatabaseBackup(offline.source(), dataDirectory.temporary(), UNLIMITED, CLOCK);
            BackupFiles.writeAtomically(archive, output -> {
                try (ZipOutputStream zip = new ZipOutputStream(output)) {
                    backup.writeMoveCopy(zip);
                }
            });
        }
    }

    TransferReport restore(Path archive) throws IOException {
        try (LockedDatabase locked = LockedDatabase.lock(database, dataDirectory)) {
            DatabaseRestore restore = new DatabaseRestore(locked.target(), UNLIMITED);
            TransferReport report;
            try (RestorableArchive verified = restore.open(archive)) {
                report = verified.restore();
            }
            restore.upgradeSchema(SqlTestDatabase.START);
            return report;
        }
    }
}

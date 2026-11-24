package gg.modl.backend.database.backup;

import gg.modl.backend.backup.BackupDiskSpace;
import gg.modl.backend.database.sql.RestoreTarget;
import gg.modl.backend.database.sql.SqliteFile;
import java.io.IOException;
import java.nio.file.Path;
import java.util.zip.ZipFile;

interface ArchiveSource {
    static ArchiveSource of(ArchiveKind kind, ZipFile archive, ArchiveManifest manifest) {
        return switch (kind) {
            case LOGICAL -> new LogicalArchiveSource(archive, manifest);
            case SNAPSHOT -> new SnapshotArchiveSource(archive, manifest);
        };
    }

    long stagingBytes() throws IOException;

    SqliteFile stage(RestoreTarget target, Path directory, BackupDiskSpace diskSpace) throws IOException;
}

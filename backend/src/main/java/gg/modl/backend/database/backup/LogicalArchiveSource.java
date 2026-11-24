package gg.modl.backend.database.backup;

import gg.modl.backend.backup.BackupDiskSpace;
import gg.modl.backend.database.sql.RestoreTarget;
import gg.modl.backend.database.sql.SchemaTable;
import gg.modl.backend.database.sql.SqliteFile;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

final class LogicalArchiveSource implements ArchiveSource {
    private static final String STAGED_FILE = "staged.db";
    private static final int BYTES_PER_ARCHIVED_BYTE = 2;

    private final ZipFile archive;
    private final ArchiveManifest manifest;

    LogicalArchiveSource(ZipFile archive, ArchiveManifest manifest) {
        this.archive = archive;
        this.manifest = manifest;
    }

    @Override
    public long stagingBytes() throws IOException {
        long bytes = 0;
        for (ArchivedTable table : manifest.tables()) {
            String name = RowLines.entryName(table.name());
            ZipEntry entry = archive.getEntry(name);
            if (entry == null) {
                throw new InvalidArchiveException("Backup is incomplete: " + name + " is missing");
            }
            bytes += Math.max(entry.getSize(), 0L);
        }
        return bytes * BYTES_PER_ARCHIVED_BYTE;
    }

    @Override
    public SqliteFile stage(RestoreTarget target, Path directory, BackupDiskSpace diskSpace) throws IOException {
        SqliteFile staged = target.stagingFile(directory.resolve(STAGED_FILE));
        staged.create(manifest.schemaVersion());
        List<SchemaTable> tables = staged.read(manifest::tablesInArchivedOrder);
        staged.load(tables, (table, rows) -> {
            long expected = manifest.archivedTable(table.name()).rows();
            long found = RowLines.read(archive, table, rows);
            if (found != expected) {
                throw InvalidArchiveException.damaged(RowLines.entryName(table.name()) + " holds " + found + " rows, "
                    + ArchiveManifest.ENTRY_NAME + " lists " + expected);
            }
        });
        staged.requireValidReferences();
        return staged;
    }
}

package gg.modl.backend.database.backup;

import gg.modl.backend.backup.BackupDiskSpace;
import gg.modl.backend.database.sql.BackupSource;
import gg.modl.backend.database.sql.DatabaseToolException;
import gg.modl.backend.database.sql.DepartureMarks;
import gg.modl.backend.database.sql.SchemaTable;
import gg.modl.backend.database.sql.SqliteFile;
import gg.modl.backend.database.sql.SqliteSnapshots;
import org.jspecify.annotations.Nullable;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public final class ArchivingDatabaseBackup implements DatabaseBackup {
    private static final String WORK_PREFIX = "modl-snapshot-";
    private static final int SNAPSHOT_COPIES_DURING_WRITE = 2;
    private static final int COPY_BUFFER_BYTES = 64 * 1024;

    private final BackupSource source;
    private final Path workRoot;
    private final BackupDiskSpace diskSpace;
    private final Clock clock;

    public ArchivingDatabaseBackup(BackupSource source, Path workRoot, BackupDiskSpace diskSpace, Clock clock) {
        this.source = source;
        this.workRoot = workRoot;
        this.diskSpace = diskSpace;
        this.clock = clock;
    }

    @Override
    public ArchiveKind preferredKind() {
        return ArchiveKind.preferredFor(source.dialect());
    }

    @Override
    public long requiredBytes(ArchiveKind kind) {
        long databaseBytes = source.sizeBytes();
        return kind == ArchiveKind.SNAPSHOT ? databaseBytes * SNAPSHOT_COPIES_DURING_WRITE : databaseBytes;
    }

    @Override
    public TransferReport write(ArchiveKind kind, ZipOutputStream zip, @Nullable UpgradeLabel label) throws IOException {
        requireSource();
        return switch (kind) {
            case LOGICAL -> writeLogical(zip, label, MoveRecord.LEFT_OUT);
            case SNAPSHOT -> writeSnapshot(zip, label);
        };
    }

    @Override
    public TransferReport writeMoveCopy(ZipOutputStream zip) throws IOException {
        requireSource();
        return writeLogical(zip, null, MoveRecord.KEPT);
    }

    private void requireSource() {
        if (!source.exists()) {
            throw new DatabaseToolException("There is no modl database to back up");
        }
    }

    private TransferReport writeLogical(ZipOutputStream zip, @Nullable UpgradeLabel label, MoveRecord moveRecord) throws IOException {
        return source.readConsistently(view -> {
            List<ArchivedTable> tables = new ArrayList<>();
            for (SchemaTable table : view.catalog().tables()) {
                long rows = moveRecord.leavesOut(table) ? RowLines.writeNone(table, zip) : RowLines.write(view, table, zip);
                tables.add(ArchivedTable.of(table, rows));
            }
            ArchiveManifest manifest = ArchiveManifest.create(ArchiveKind.LOGICAL, clock.instant(), view.schemaVersion(), tables, label);
            manifest.writeTo(zip);
            return manifest.report();
        });
    }

    private TransferReport writeSnapshot(ZipOutputStream zip, @Nullable UpgradeLabel label) throws IOException {
        SqliteSnapshots snapshots = source.snapshots().orElseThrow(() -> new DatabaseToolException(
            "Snapshot backups need the built-in SQLite database; write a logical backup instead"));
        try (WorkDirectory work = WorkDirectory.createReplacingAbandoned(workRoot, WORK_PREFIX, clock.instant())) {
            diskSpace.require(work.path(), source.sizeBytes());
            SqliteFile copy = snapshots.copyInto(work.path().resolve(SnapshotArchiveSource.DATABASE_ENTRY));
            copy.forgetDatabaseMove();
            ArchiveManifest manifest = describe(copy, label);
            manifest.writeTo(zip);
            zip.setLevel(Deflater.BEST_SPEED);
            zip.putNextEntry(new ZipEntry(SnapshotArchiveSource.DATABASE_ENTRY));
            copyWhileServing(copy.path(), zip);
            zip.closeEntry();
            zip.setLevel(Deflater.DEFAULT_COMPRESSION);
            return manifest.report();
        }
    }

    private ArchiveManifest describe(SqliteFile copy, @Nullable UpgradeLabel label) throws IOException {
        return copy.read(view -> {
            List<ArchivedTable> tables = view.catalog().tables().stream()
                .map(table -> ArchivedTable.of(table, view.count(table)))
                .toList();
            return ArchiveManifest.create(ArchiveKind.SNAPSHOT, clock.instant(), view.schemaVersion(), tables, label);
        });
    }

    private enum MoveRecord {
        KEPT,
        LEFT_OUT;

        boolean leavesOut(SchemaTable table) {
            return this == LEFT_OUT && DepartureMarks.isMarkTable(table.name());
        }
    }

    private void copyWhileServing(Path file, ZipOutputStream zip) throws IOException {
        byte[] buffer = new byte[COPY_BUFFER_BYTES];
        try (InputStream input = Files.newInputStream(file)) {
            int read;
            while ((read = input.read(buffer)) >= 0) {
                source.requireServing();
                zip.write(buffer, 0, read);
            }
        }
    }
}

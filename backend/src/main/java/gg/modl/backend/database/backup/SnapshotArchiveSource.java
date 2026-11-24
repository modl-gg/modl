package gg.modl.backend.database.backup;

import gg.modl.backend.backup.BackupDiskSpace;
import gg.modl.backend.backup.VerifiedEntryStream;
import gg.modl.backend.database.sql.RestoreTarget;
import gg.modl.backend.database.sql.SchemaTable;
import gg.modl.backend.database.sql.SqliteFile;
import java.io.EOFException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;

final class SnapshotArchiveSource implements ArchiveSource {
    static final String DATABASE_ENTRY = "modl.db";

    private static final String MIGRATED_SCHEMA_FILE = "schema.db";
    private static final String CONVERTED_FILE = "converted.db";

    private final ZipFile archive;
    private final ArchiveManifest manifest;

    SnapshotArchiveSource(ZipFile archive, ArchiveManifest manifest) {
        this.archive = archive;
        this.manifest = manifest;
    }

    @Override
    public long stagingBytes() throws IOException {
        return Math.max(databaseEntry().getSize(), 0L);
    }

    @Override
    public SqliteFile stage(RestoreTarget target, Path directory, BackupDiskSpace diskSpace) throws IOException {
        SqliteFile extracted = target.stagingFile(directory.resolve(DATABASE_ENTRY));
        extract(extracted.path());
        extracted.requirePlainSchema();
        extracted.requireIntact();
        extracted.requireValidReferences();
        extracted.migrateTo(manifest.schemaVersion());
        requireMatchesManifest(extracted);
        if (target.dialect().isExternal()) {
            requireMigratedSchema(target, directory);
        }
        if (extracted.usesIncrementalAutoVacuum()) {
            return extracted;
        }
        diskSpace.require(directory, Files.size(extracted.path()));
        return extracted.convertedToIncrementalAutoVacuum(directory.resolve(CONVERTED_FILE));
    }

    private void requireMatchesManifest(SqliteFile extracted) throws IOException {
        extracted.read(view -> {
            manifest.requireDescribes(view.schemaVersion(), view.catalog());
            for (SchemaTable table : view.catalog().tables()) {
                long expected = manifest.archivedTable(table.name()).rows();
                long found = view.count(table);
                if (found != expected) {
                    throw InvalidArchiveException.damaged(table.name() + " holds " + found + " rows, "
                        + ArchiveManifest.ENTRY_NAME + " lists " + expected);
                }
            }
            return view.schemaVersion();
        });
    }

    private void requireMigratedSchema(RestoreTarget target, Path directory) throws IOException {
        SqliteFile migrated = target.stagingFile(directory.resolve(MIGRATED_SCHEMA_FILE));
        migrated.create(manifest.schemaVersion());
        migrated.read(manifest::tablesInArchivedOrder);
    }

    private void extract(Path target) throws IOException {
        try (VerifiedEntryStream input = VerifiedEntryStream.open(archive, databaseEntry())) {
            Files.copy(input, target);
            input.requireIntact();
        } catch (ZipException | EOFException e) {
            throw InvalidArchiveException.unreadable(DATABASE_ENTRY, e);
        }
    }

    private ZipEntry databaseEntry() throws InvalidArchiveException {
        ZipEntry entry = archive.getEntry(DATABASE_ENTRY);
        if (entry == null) {
            throw new InvalidArchiveException("Backup is incomplete: " + DATABASE_ENTRY + " is missing");
        }
        return entry;
    }
}

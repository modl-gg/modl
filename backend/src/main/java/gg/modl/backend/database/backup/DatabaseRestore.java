package gg.modl.backend.database.backup;

import gg.modl.backend.backup.BackupDiskSpace;
import gg.modl.backend.backup.FileTrees;
import gg.modl.backend.database.sql.RestoreTarget;
import gg.modl.backend.database.sql.SchemaCatalog;
import gg.modl.backend.database.sql.SqliteFile;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Set;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;

public final class DatabaseRestore {
    private static final String WORK_PREFIX = ".modl-restore-";

    private final RestoreTarget target;
    private final BackupDiskSpace diskSpace;

    public DatabaseRestore(RestoreTarget target, BackupDiskSpace diskSpace) {
        this.target = target;
        this.diskSpace = diskSpace;
    }

    public RestorableArchive open(Path archivePath) throws IOException {
        return open(archivePath, Set.of());
    }

    public RestorableArchive open(Path archivePath, Set<String> newerVersionTables) throws IOException {
        Set<String> recordedTables = Set.copyOf(newerVersionTables);
        try (ZipFile archive = openZip(archivePath)) {
            ArchiveManifest manifest = ArchiveManifest.read(archive);
            ArchiveSource source = ArchiveSource.of(manifest.kind(), archive, manifest);
            Path workRoot = target.workDirectory();
            Files.createDirectories(workRoot);
            FileTrees.deleteChildrenStartingWith(workRoot, WORK_PREFIX);
            diskSpace.require(workRoot, source.stagingBytes());
            WorkDirectory work = WorkDirectory.create(workRoot, WORK_PREFIX);
            try {
                SqliteFile staged = source.stage(target, work.path(), diskSpace);
                target.requireReplaceable(verifiedCatalog(manifest.kind(), staged), recordedTables);
                return new RestorableArchive(target, work, staged, recordedTables, manifest.report());
            } catch (IOException | RuntimeException e) {
                FileTrees.deleteQuietly(work.path(), e);
                throw e;
            }
        }
    }

    public RestorableArchive openUpgradeBackup(Path archivePath) throws IOException {
        Set<String> createdByTheUpgrade;
        try (ZipFile archive = openZip(archivePath)) {
            createdByTheUpgrade = Set.copyOf(ArchiveManifest.read(archive).createdTables());
        }
        return open(archivePath, createdByTheUpgrade);
    }

    public void upgradeSchema(Instant now) {
        target.upgradeSchema(now);
    }

    private SchemaCatalog verifiedCatalog(ArchiveKind kind, SqliteFile staged) throws IOException {
        return staged.read(view -> {
            if (kind == ArchiveKind.LOGICAL || target.dialect().isExternal()) {
                StorableValues.require(view, target.dialect());
            }
            return view.catalog();
        });
    }

    private static ZipFile openZip(Path archivePath) throws IOException {
        try {
            return new ZipFile(archivePath.toFile());
        } catch (ZipException e) {
            throw new InvalidArchiveException("Not a modl backup: " + archivePath + " is not a readable zip file", e);
        }
    }
}

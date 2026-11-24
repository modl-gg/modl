package gg.modl.backend.cli;

import gg.modl.backend.backup.ArchivedStorage;
import gg.modl.backend.backup.BackupDiskSpace;
import gg.modl.backend.backup.BackupFiles;
import gg.modl.backend.backup.BackupSeries;
import gg.modl.backend.backup.BackupSeries.WrittenBackup;
import gg.modl.backend.backup.StorageArchive;
import gg.modl.backend.backup.StorageFolder;
import gg.modl.backend.backup.UploadsRestoreMarker;
import gg.modl.backend.database.backup.ArchivingDatabaseBackup;
import gg.modl.backend.database.backup.DatabaseBackup;
import gg.modl.backend.database.backup.DatabaseRestore;
import gg.modl.backend.database.backup.TransferReport;
import gg.modl.backend.database.sql.BackupSource;
import gg.modl.backend.database.sql.LockedDatabase;
import gg.modl.backend.database.sql.OfflineDatabase;
import gg.modl.backend.database.sql.RestoreTarget;
import gg.modl.backend.infrastructure.util.FailureDescriptions;
import gg.modl.backend.settings.data.SettingsType;
import gg.modl.backend.settings.service.SettingsCodec;
import gg.modl.backend.storage.StorageType;
import gg.modl.backend.storage.backend.StorageBackendSettings;
import gg.modl.backend.update.JacksonJsonMaps;
import gg.modl.update.UpdateFiles;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.ZipOutputStream;

record CliContext(CliSettings settings, PrintStream out, Clock clock) {
    private static final int SAFETY_BACKUPS_KEPT = 3;
    private static final SettingsCodec<StorageBackendSettings> STORAGE_BACKEND_CODEC =
        SettingsCodec.of(StorageBackendSettings.class, () -> StorageBackendSettings.DEFAULTS);

    OfflineDatabase openConfiguredDatabase() {
        return OfflineDatabase.open(settings.database(), settings.dataDirectory());
    }

    LockedDatabase lockConfiguredDatabase() {
        return LockedDatabase.lock(settings.database(), settings.dataDirectory());
    }

    DatabaseBackup backupOf(BackupSource source) {
        return new ArchivingDatabaseBackup(source, settings.dataDirectory().temporary(), BackupDiskSpace.SYSTEM, clock);
    }

    UpdateFiles updateFiles() {
        return new UpdateFiles(settings.dataDirectory().root(), JacksonJsonMaps.INSTANCE);
    }

    DatabaseRestore restoreInto(RestoreTarget target) {
        return new DatabaseRestore(target, BackupDiskSpace.SYSTEM);
    }

    void requireExistingDatabase(BackupSource source) {
        if (!source.exists()) {
            String location = source.dialect().isExternal() ? "the configured database" : settings.dataDirectory().database().toString();
            throw new CliFailureException("There is no modl database in " + location
                + "; check MODL_DATA_DIR and MODL_DATABASE_URL");
        }
    }

    StorageBackendSettings storageBackend(BackupSource source) throws IOException {
        Optional<StorageType> configured = settings.storage().configuredType();
        if (configured.isPresent()) {
            return StorageBackendSettings.managedBy(configured.get());
        }
        if (!source.exists()) {
            return StorageBackendSettings.DEFAULTS;
        }
        return source.readConsistently(view -> view.settingsDocument(SettingsType.STORAGE_BACKEND.id()))
            .map(STORAGE_BACKEND_CODEC::decodeJson)
            .orElse(StorageBackendSettings.DEFAULTS);
    }

    boolean storageIsManagedAsS3() {
        return settings.storage().configuredType().filter(type -> type == StorageType.S3).isPresent();
    }

    UploadsRestoreMarker uploadsRestoreMarker() {
        return new UploadsRestoreMarker(settings.dataDirectory().root());
    }

    StorageFolder localStorage() {
        return new StorageFolder(settings.dataDirectory().storage());
    }

    TransferReport writeBackup(BackupSource source, Path target) throws IOException {
        DatabaseBackup backup = backupOf(source);
        return writeArchive(target, backup.requiredBytes(backup.preferredKind()), zip -> backup.write(backup.preferredKind(), zip));
    }

    BackupWithStorageReport writeBackup(BackupSource source, Path target, StorageFolder storage) throws IOException {
        DatabaseBackup backup = backupOf(source);
        return writeArchive(target, backup.requiredBytes(backup.preferredKind()) + storage.sizeBytes(), zip -> {
            TransferReport database = backup.write(backup.preferredKind(), zip);
            ArchivedStorage files = StorageArchive.write(storage, zip);
            return new BackupWithStorageReport(database, files);
        });
    }

    void writeSafetyBackup(LockedDatabase database, String prefix) {
        try {
            if (database.target().holdsUnfinishedRestore()) {
                out.println("The database holds an unfinished restore, so no new safety backup is written;"
                    + " the earlier safety backups are kept");
                return;
            }
            BackupSource source = database.source();
            if (!source.exists()) {
                return;
            }
            DatabaseBackup backup = backupOf(source);
            BackupSeries series = new BackupSeries(new BackupFiles(settings.dataDirectory().backups(), clock), BackupDiskSpace.SYSTEM,
                prefix, SAFETY_BACKUPS_KEPT);
            WrittenBackup written = backup.writeInto(series, backup.preferredKind());
            for (Path removed : written.removedToMakeRoom()) {
                out.println("Removed the older safety backup " + removed + " to make room");
            }
            out.println("Saved the current data to " + written.archive() + " before changing it");
        } catch (IOException | RuntimeException e) {
            throw new CliFailureException("The current database could not be saved first, so it was not changed ("
                + FailureDescriptions.describe(e) + "). " + damagedDatabaseRemedy(), e);
        }
    }

    private String damagedDatabaseRemedy() {
        if (settings.database().dialect().isExternal()) {
            return "If modl's tables in that database are damaged, set MODL_DATABASE_URL to another database and run the command"
                + " again; never empty a database that other software shares.";
        }
        return "If that database is damaged, move modl.db and the files whose names start with modl.db- out of "
            + settings.dataDirectory().database() + ", then run the command again.";
    }

    private static Path directoryOf(Path target) {
        Path absolute = target.toAbsolutePath();
        Path directory = absolute.getParent();
        return directory == null ? absolute : directory;
    }

    private static <T> T writeArchive(Path target, long estimatedBytes, ArchiveContent<T> content) throws IOException {
        BackupDiskSpace.SYSTEM.require(directoryOf(target), estimatedBytes);
        AtomicReference<T> report = new AtomicReference<>();
        BackupFiles.writeAtomically(target, destination -> {
            try (ZipOutputStream zip = new ZipOutputStream(new BufferedOutputStream(destination))) {
                report.set(content.writeTo(zip));
            }
        });
        return report.get();
    }

    @FunctionalInterface
    private interface ArchiveContent<T> {
        T writeTo(ZipOutputStream zip) throws IOException;
    }
}

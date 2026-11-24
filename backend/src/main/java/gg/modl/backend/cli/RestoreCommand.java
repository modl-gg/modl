package gg.modl.backend.cli;

import gg.modl.backend.backup.BackupDiskSpace;
import gg.modl.backend.backup.InstalledStorage;
import gg.modl.backend.backup.StagedStorage;
import gg.modl.backend.backup.StorageArchive;
import gg.modl.backend.backup.StorageFolder;
import gg.modl.backend.backup.UploadsRestoreMarker;
import gg.modl.backend.database.backup.DatabaseRestore;
import gg.modl.backend.database.backup.RestorableArchive;
import gg.modl.backend.database.backup.TransferReport;
import gg.modl.backend.database.sql.LockedDatabase;
import gg.modl.backend.infrastructure.util.FailureDescriptions;
import gg.modl.update.UpdateFiles;
import gg.modl.update.UpdateStateException;
import gg.modl.update.UpdateTransaction;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Set;

final class RestoreCommand implements CliCommand {
    private static final String INPUT = "input";
    private static final String DATABASE_ONLY = "database-only";
    private static final String SAFETY_BACKUP_PREFIX = "pre-restore-";

    @Override
    public String name() {
        return "restore";
    }

    @Override
    public String usage() {
        return "restore --input <file.zip> [--database-only]";
    }

    @Override
    public Set<String> options() {
        return Set.of(INPUT);
    }

    @Override
    public Set<String> flags() {
        return Set.of(DATABASE_ONLY);
    }

    @Override
    public void run(CliArguments arguments, CliContext context) throws Exception {
        Path input = Path.of(arguments.required(INPUT)).toAbsolutePath().normalize();
        if (!Files.isRegularFile(input)) {
            throw new CliUsageException("Backup file not found: " + input);
        }
        if (context.localStorage().contains(input)) {
            throw new CliUsageException("Move the backup out of " + context.localStorage().root() + " before restoring it");
        }
        try (LockedDatabase database = context.lockConfiguredDatabase()) {
            context.uploadsRestoreMarker().warning().ifPresent(context.out()::println);
            DatabaseRestore restore = context.restoreInto(database.target());
            String contents;
            try (RestorableArchive archive = restore.open(input)) {
                contents = arguments.flag(DATABASE_ONLY)
                    ? restoreDatabase(archive, database, context).describe()
                    : restoreEverything(input, archive, database, context);
            }
            upgrade(restore, context);
            cancelUnfinishedUpdate(context);
            context.out().println("Restored " + input + " (" + contents + ")");
        }
    }

    private static String restoreEverything(Path input, RestorableArchive archive, LockedDatabase database, CliContext context)
        throws IOException {
        try (StorageArchive storage = StorageArchive.open(input)) {
            return storage.summary().isPresent()
                ? restoreWithStorage(input, archive, storage, database, context)
                : restoreDatabase(archive, database, context).describe();
        }
    }

    private static TransferReport restoreDatabase(RestorableArchive archive, LockedDatabase database, CliContext context)
        throws IOException {
        context.writeSafetyBackup(database, SAFETY_BACKUP_PREFIX);
        return archive.restore();
    }

    private static String restoreWithStorage(Path input, RestorableArchive archive, StorageArchive storage, LockedDatabase database,
                                             CliContext context) throws IOException {
        if (context.storageIsManagedAsS3()) {
            throw new IllegalStateException("This backup contains uploaded files, but uploads are stored in S3 (MODL_STORAGE=s3);"
                + " add --" + DATABASE_ONLY + " to restore only the database");
        }
        StorageFolder folder = context.localStorage();
        try (StagedStorage staged = folder.stage(storage, BackupDiskSpace.SYSTEM)) {
            context.writeSafetyBackup(database, SAFETY_BACKUP_PREFIX);
            context.out().println("The safety backup holds the database only; the uploaded files in " + folder.root()
                + " are replaced with the ones in the backup");
            UploadsRestoreMarker marker = context.uploadsRestoreMarker();
            marker.begin(input);
            TransferReport report = archive.restore();
            InstalledStorage installed = installUploads(staged);
            installed.commit().forEach(leftover -> context.out().println("Could not remove the temporary folder " + leftover
                + "; delete it by hand"));
            marker.clear();
            return report.describe() + ", " + staged.summary().describe();
        }
    }

    private static InstalledStorage installUploads(StagedStorage staged) {
        try {
            return staged.install();
        } catch (IOException e) {
            throw new CliFailureException("The database was restored, but the uploaded files were not replaced ("
                + FailureDescriptions.describe(e) + "). Do not start modl before you have run the same restore again.", e);
        }
    }

    private static void cancelUnfinishedUpdate(CliContext context) throws IOException {
        UpdateFiles files = context.updateFiles();
        Optional<UpdateTransaction> unfinished = readableUpdate(files);
        files.deletePending();
        unfinished.ifPresent(update -> context.out().println("Cancelled the unfinished update from modl " + update.from() + " to "
            + update.to() + "; run update again to install it"));
    }

    private static Optional<UpdateTransaction> readableUpdate(UpdateFiles files) {
        try {
            return Optional.ofNullable(files.readPending());
        } catch (UpdateStateException damaged) {
            return Optional.empty();
        }
    }

    private static void upgrade(DatabaseRestore restore, CliContext context) {
        try {
            restore.upgradeSchema(context.clock().instant());
        } catch (RuntimeException e) {
            throw new CliFailureException("The backup was restored, but the database could not be upgraded to this version of modl ("
                + FailureDescriptions.describe(e) + ")", e);
        }
    }
}

package gg.modl.backend.cli;

import gg.modl.backend.backup.FileTrees;
import gg.modl.backend.backup.StorageFolder;
import gg.modl.backend.instance.PanelLocations;
import gg.modl.backend.storage.backend.StorageBackendSettings;
import gg.modl.backend.database.sql.BackupSource;
import gg.modl.backend.database.sql.OfflineDatabase;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Set;

final class BackupCommand implements CliCommand {
    private static final String OUTPUT = "output";
    private static final String WITH_STORAGE = "with-storage";

    @Override
    public String name() {
        return "backup";
    }

    @Override
    public String usage() {
        return "backup --output <file.zip> [--with-storage]";
    }

    @Override
    public Set<String> options() {
        return Set.of(OUTPUT);
    }

    @Override
    public Set<String> flags() {
        return Set.of(WITH_STORAGE);
    }

    @Override
    public void run(CliArguments arguments, CliContext context) throws Exception {
        Path output = Path.of(arguments.required(OUTPUT)).toAbsolutePath().normalize();
        Path databaseDirectory = context.settings().dataDirectory().database();
        if (FileTrees.contains(databaseDirectory, output)) {
            throw new CliUsageException("Write the backup outside " + databaseDirectory + "; it could replace the database itself");
        }
        String contents = arguments.flag(WITH_STORAGE) ? backUpWithStorage(output, context) : backUpDatabase(output, context);
        context.out().println("Backup written to " + output + " (" + contents + ")");
    }

    private static String backUpDatabase(Path output, CliContext context) throws IOException {
        try (OfflineDatabase database = context.openConfiguredDatabase()) {
            BackupSource source = database.source();
            context.requireExistingDatabase(source);
            return context.writeBackup(source, output).describe();
        }
    }

    private static String backUpWithStorage(Path output, CliContext context) throws IOException {
        StorageFolder storage = context.localStorage();
        if (storage.contains(output)) {
            throw new CliUsageException("Write the backup outside " + storage.root() + "; a backup cannot contain itself");
        }
        try (OfflineDatabase database = context.openConfiguredDatabase()) {
            BackupSource source = database.source();
            context.requireExistingDatabase(source);
            requireAllUploadsInTheLocalFolder(context.storageBackend(source));
            return context.writeBackup(source, output, storage).describe();
        }
    }

    private static void requireAllUploadsInTheLocalFolder(StorageBackendSettings backend) {
        if (backend.migrationTarget().isPresent()) {
            throw new IllegalStateException("--with-storage needs every uploaded file in the local storage folder, but the files are"
                + " being moved between storages; wait until the move in " + PanelLocations.STORAGE + " finishes");
        }
        if (!backend.holdsEveryUploadInTheLocalFolder()) {
            throw new IllegalStateException("--with-storage backs up the local storage folder, but uploads are stored in S3;"
                + " back up the bucket with your storage provider's tools");
        }
    }
}

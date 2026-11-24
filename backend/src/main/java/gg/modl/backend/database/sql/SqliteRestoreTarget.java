package gg.modl.backend.database.sql;

import gg.modl.backend.backup.BackupDiskSpace;
import gg.modl.backend.database.DatabaseDialect;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import javax.sql.DataSource;

final class SqliteRestoreTarget implements RestoreTarget {
    private static final String WRITE_AHEAD_LOG = "-wal";
    private static final String SHARED_MEMORY = "-shm";
    private static final String ROLLBACK_JOURNAL = "-journal";
    private static final String MERGED_FILE = "merged.db";
    private static final String CONVERTED_MERGED_FILE = "merged-converted.db";

    private final DatabaseAccess access;
    private final SqliteConnections live;
    private final Path file;

    SqliteRestoreTarget(DatabaseAccess access) {
        this.access = access;
        this.file = access.databaseFile();
        this.live = access.sqliteConnections(file);
    }

    @Override
    public DatabaseDialect dialect() {
        return DatabaseDialect.SQLITE;
    }

    @Override
    public Path workDirectory() {
        return access.dataDirectory().database();
    }

    @Override
    public SqliteFile stagingFile(Path stagedFile) {
        access.prepareSqlite();
        return access.sqliteFile(stagedFile);
    }

    @Override
    public List<String> modlTables() {
        if (!SqliteBackupSource.hasContent(file)) {
            return List.of();
        }
        SchemaState current = access.sqliteFile(file).state();
        Set<String> present = new TreeSet<>(current.ownedTables(access.schemas(), Set.of()));
        if (current.version().isPresent()) {
            present.add(ModlSchema.HISTORY_TABLE);
        }
        return List.copyOf(present);
    }

    @Override
    public boolean holdsUnfinishedRestore() {
        return false;
    }

    @Override
    public void requireReplaceable(SchemaCatalog replacement, Set<String> newerVersionTables) {
        access.requireNoOpenConnections();
        if (!SqliteBackupSource.hasContent(file)) {
            requireNoPendingChanges(file);
            return;
        }
        try {
            access.sqliteFile(file).requireNoBlockers(replacement, newerVersionTables);
        } catch (DatabaseToolException e) {
            throw new DatabaseToolException(e.getMessage() + " To replace a damaged or unrecognised database file, move " + file
                + " and the files whose names start with " + file.getFileName() + "- to a safe place, then run the command again.", e);
        }
    }

    @Override
    public void replaceWith(SqliteFile verified, Set<String> newerVersionTables) throws IOException {
        access.requireNoOpenConnections();
        access.fence().requireServing();
        SqliteFile installed = holdsOtherObjects(newerVersionTables) ? mergedWithCurrent(verified, newerVersionTables) : verified;
        requireNoPendingChanges(installed.path());
        DurableFiles.forceFile(installed.path());
        if (SqliteBackupSource.hasContent(file)) {
            checkpointCurrentDatabase();
        }
        requireNoPendingChanges(file);
        for (String suffix : List.of(WRITE_AHEAD_LOG, SHARED_MEMORY, ROLLBACK_JOURNAL)) {
            Files.deleteIfExists(companion(file, suffix));
        }
        Files.move(installed.path(), file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        DurableFiles.forceDirectory(live.databaseDirectory());
        SqliteDatabaseFile.bootstrap(live, access.tracker(), access.notice());
    }

    @Override
    public void upgradeSchema(Instant now) {
        DataSource migrations = access.tracker().track(live.migration(), ConnectionRole.MIGRATION);
        OfflineUpgrade.run(access, DatabaseDialect.SQLITE, migrations, now);
    }

    private boolean holdsOtherObjects(Set<String> newerVersionTables) {
        if (!SqliteBackupSource.hasContent(file)) {
            return false;
        }
        SqliteFile current = access.sqliteFile(file);
        SchemaState state = current.state();
        Set<String> modls = new HashSet<>(state.ownedTables(access.schemas(), newerVersionTables));
        modls.addAll(state.ownedControlTables());
        return current.holdsObjectsOutside(modls);
    }

    private SqliteFile mergedWithCurrent(SqliteFile verified, Set<String> newerVersionTables) throws IOException {
        Path directory = verified.directory();
        BackupDiskSpace.SYSTEM.require(directory, access.sqliteFile(file).sizeBytes() + Files.size(verified.path()));
        SqliteFile merged = new SqliteSnapshots(access, live).copyInto(directory.resolve(MERGED_FILE));
        verified.read(view -> {
            merged.replaceOwnedTables(view, newerVersionTables);
            return view.schemaVersion();
        });
        merged.requireIntact();
        return merged.usesIncrementalAutoVacuum()
            ? merged
            : merged.convertedToIncrementalAutoVacuum(directory.resolve(CONVERTED_MERGED_FILE));
    }

    private void checkpointCurrentDatabase() {
        DataSource checkpoints = access.tracker().track(live.checkpoint(), ConnectionRole.MAINTENANCE);
        try (Connection connection = checkpoints.getConnection()) {
            if (access.statements().number(connection, "PRAGMA wal_checkpoint(TRUNCATE)") > 0) {
                throw new DatabaseToolException("Another program is using " + file + "; close it, then run the command again."
                    + " The database was not changed.");
            }
        } catch (SQLException e) {
            throw DatabaseToolException.failedTo("checkpoint the current database", e);
        }
    }

    private static void requireNoPendingChanges(Path databaseFile) {
        for (String suffix : List.of(WRITE_AHEAD_LOG, ROLLBACK_JOURNAL)) {
            Path companion = companion(databaseFile, suffix);
            if (SqliteBackupSource.hasContent(companion)) {
                throw new DatabaseToolException(companion + " still holds changes of a database, so it was not replaced."
                    + " Move that file to a safe place, then run the command again.");
            }
        }
    }

    private static Path companion(Path databaseFile, String suffix) {
        return databaseFile.resolveSibling(databaseFile.getFileName() + suffix);
    }
}

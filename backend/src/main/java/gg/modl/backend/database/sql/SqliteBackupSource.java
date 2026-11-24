package gg.modl.backend.database.sql;

import gg.modl.backend.database.DatabaseDialect;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

final class SqliteBackupSource implements BackupSource {
    private final DatabaseAccess access;
    private final Path file;

    SqliteBackupSource(DatabaseAccess access) {
        this.access = access;
        this.file = access.databaseFile();
    }

    static boolean hasContent(Path file) {
        try {
            return Files.exists(file) && Files.size(file) > 0;
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read " + file, e);
        }
    }

    @Override
    public DatabaseDialect dialect() {
        return DatabaseDialect.SQLITE;
    }

    @Override
    public boolean exists() {
        return hasContent(file) && access.sqliteFile(file).state().version().isPresent();
    }

    @Override
    public long sizeBytes() {
        return hasContent(file) ? access.sqliteFile(file).sizeBytes() : 0L;
    }

    @Override
    public void requireServing() {
        access.fence().requireServing();
    }

    @Override
    public <T> T readConsistently(ViewReader<T> reader) throws IOException {
        return existingFile().read(reader);
    }

    @Override
    public Optional<SqliteSnapshots> snapshots() {
        return Optional.of(new SqliteSnapshots(access, access.sqliteConnections(file)));
    }

    private SqliteFile existingFile() {
        if (!hasContent(file)) {
            throw new DatabaseToolException("There is no database at " + file);
        }
        return access.sqliteFile(file);
    }

    @Override
    public Set<String> tablesAnUpgradeCreates() {
        Set<String> created = new HashSet<>(access.schemas().latest().tables());
        created.removeAll(existingFile().state().tables());
        return created;
    }
}

package gg.modl.backend.database.sql;

import gg.modl.backend.database.DatabaseDialect;
import java.io.IOException;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import javax.sql.DataSource;

final class ExternalRestoreTarget implements RestoreTarget {
    private static final String MYSQL_BATCH_REWRITE = "rewriteBatchedStatements";

    private final DatabaseAccess access;
    private final DatabaseDialect dialect;
    private final DataSource connections;
    private final OwnedTableReplacement replacement;

    ExternalRestoreTarget(DatabaseAccess access) {
        this.access = access;
        this.dialect = access.database().dialect();
        DriverDataSource sessions = ExternalSessions.migration(DriverDataSource.of(access.database()), dialect);
        this.connections = access.tracked(sessions);
        DataSource loads = access.tracked(dialect == DatabaseDialect.MYSQL
            ? sessions.withProperty(MYSQL_BATCH_REWRITE, "true")
            : sessions);
        this.replacement = new OwnedTableReplacement(access, dialect, connections, loads);
    }

    @Override
    public DatabaseDialect dialect() {
        return dialect;
    }

    @Override
    public Path workDirectory() {
        return access.dataDirectory().temporary();
    }

    @Override
    public SqliteFile stagingFile(Path stagedFile) {
        access.prepareSqlite();
        return access.sqliteFile(stagedFile);
    }

    @Override
    public List<String> modlTables() {
        SchemaState current = state();
        Set<String> present = new TreeSet<>(current.ownedTables(access.schemas(), Set.of()));
        if (current.unfinished()) {
            present.add(ModlSchema.UNFINISHED_TABLE);
        }
        if (current.version().isPresent()) {
            present.add(ModlSchema.HISTORY_TABLE);
        }
        return List.copyOf(present);
    }

    @Override
    public boolean holdsUnfinishedRestore() {
        SchemaState current = state();
        return current.unfinished() && current.version().isEmpty();
    }

    @Override
    public void requireReplaceable(SchemaCatalog replacementSchema, Set<String> newerVersionTables) {
        access.requireNoOpenConnections();
        try (Connection connection = connections.getConnection()) {
            SchemaState current = SchemaState.read(access, connection, dialect, connections);
            new RestoreBlockers(access, dialect).requireNone(connection, current, replacementSchema, newerVersionTables);
        } catch (SQLException e) {
            throw DatabaseToolException.failedTo("read the target database", e);
        }
    }

    @Override
    public void replaceWith(SqliteFile verified, Set<String> newerVersionTables) throws IOException {
        access.requireNoOpenConnections();
        verified.read(view -> {
            replacement.replace(view, newerVersionTables);
            return view.schemaVersion();
        });
    }

    @Override
    public void upgradeSchema(Instant now) {
        OfflineUpgrade.run(access, dialect, connections, now);
    }

    private SchemaState state() {
        try (Connection connection = connections.getConnection()) {
            return SchemaState.read(access, connection, dialect, connections);
        } catch (SQLException e) {
            throw DatabaseToolException.failedTo("read the target database", e);
        }
    }
}

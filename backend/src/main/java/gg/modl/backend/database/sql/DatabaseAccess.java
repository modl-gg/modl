package gg.modl.backend.database.sql;

import gg.modl.backend.database.DataDirectoryProperties;
import gg.modl.backend.database.DatabaseDialect;
import gg.modl.backend.infrastructure.logging.OperatorNotice;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import javax.sql.DataSource;
import lombok.Getter;
import lombok.experimental.Accessors;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.jooq.impl.DefaultConfiguration;
import org.jooq.impl.DefaultExecuteListenerProvider;

@Getter
@Accessors(fluent = true)
final class DatabaseAccess {
    private static final String TOOLS_DIRECTORY = "tools";

    private final SqlDatabaseProperties database;
    private final DataDirectoryProperties dataDirectory;
    private final ConnectionTracker tracker;
    private final RunningStatements running;
    private final StatementDeadline statementDeadline;
    private final InstanceFence fence;
    private final OperatorNotice notice;
    private final ClassLoader migrations;
    private final MigratedSchemas schemas;

    DatabaseAccess(SqlDatabaseProperties database, DataDirectoryProperties dataDirectory, ConnectionTracker tracker,
                   RunningStatements running, StatementDeadline statementDeadline, InstanceFence fence, OperatorNotice notice,
                   ClassLoader migrations) {
        this.database = database;
        this.dataDirectory = dataDirectory;
        this.tracker = tracker;
        this.running = running;
        this.statementDeadline = statementDeadline;
        this.fence = fence;
        this.notice = notice;
        this.migrations = migrations;
        this.schemas = new MigratedSchemas(this);
    }

    FluentConfiguration flyway(DatabaseDialect dialect, DataSource connections) {
        return DatabaseMigration.configure(dialect, connections, migrations);
    }

    BackupSource backupSource() {
        return database.dialect().isExternal() ? new ExternalBackupSource(this) : new SqliteBackupSource(this);
    }

    RestoreTarget restoreTarget() {
        return database.dialect().isExternal() ? new ExternalRestoreTarget(this) : new SqliteRestoreTarget(this);
    }

    Path databaseFile() {
        return SqliteLocations.databaseFile(dataDirectory);
    }

    SqliteFile sqliteFile(Path file) {
        return new SqliteFile(this, sqliteConnections(file));
    }

    SqliteConnections sqliteConnections(Path file) {
        return new SqliteConnections(file, sqliteTemporaryDirectory());
    }

    Path sqliteTemporaryDirectory() {
        return SqliteLocations.temporaryDirectory(dataDirectory);
    }

    void prepareSqlite() {
        Path temporary = sqliteTemporaryDirectory();
        try {
            Files.createDirectories(temporary);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not create the SQLite temporary directory " + temporary, e);
        }
        SqliteNativeLibrary.initialize(SqliteLocations.nativeParent(database, dataDirectory).resolve(TOOLS_DIRECTORY));
    }

    DataSource tracked(DataSource connections) {
        return tracker.track(connections, ConnectionRole.SNAPSHOT);
    }

    SqlStatements statements() {
        return new SqlStatements(running);
    }

    DSLContext queries(Connection connection, DatabaseDialect dialect) {
        DefaultConfiguration configuration = new DefaultConfiguration();
        configuration.set(dialect.sqlDialect());
        configuration.set(connection);
        configuration.set(JooqConfiguration.settings());
        configuration.set(DefaultExecuteListenerProvider.providers(statementDeadline));
        return DSL.using(configuration);
    }

    void requireNoOpenConnections() {
        if (tracker.openConnections() > 0) {
            throw new DatabaseToolException("The database can only be replaced while modl has no open connections to it;"
                + " replace it before the connection pools open");
        }
    }
}

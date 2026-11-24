package gg.modl.backend.database.sql;

import gg.modl.backend.database.DataDirectoryProperties;
import gg.modl.backend.database.DatabaseDialect;
import gg.modl.backend.infrastructure.logging.OperatorNotice;
import gg.modl.backend.support.sql.DialectTarget;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import javax.sql.DataSource;
import org.flywaydb.core.api.configuration.FluentConfiguration;

final class MigrationSteps {
    private final DatabaseDialect dialect;
    private final DataSource connections;

    private MigrationSteps(DatabaseDialect dialect, DataSource connections) {
        this.dialect = dialect;
        this.connections = connections;
    }

    static MigrationSteps on(DialectTarget target, SqlDatabaseProperties database, DataDirectoryProperties dataDirectory)
        throws IOException {
        ConnectionTracker tracker = new ConnectionTracker();
        if (!target.isSqlite()) {
            return new MigrationSteps(target.dialect(), new ExternalDatabaseConnections(database, tracker).migration());
        }
        Path temporary = dataDirectory.temporary().resolve("sqlite");
        Path databaseDirectory = Files.createDirectories(dataDirectory.database());
        return new MigrationSteps(target.dialect(),
            SqliteDatabaseConnections.prepare(databaseDirectory, temporary, temporary, tracker, new OperatorNotice()).migration());
    }

    DataSource connections() {
        return connections;
    }

    List<String> pendingVersions() {
        return Arrays.stream(flyway().load().info().pending())
            .map(migration -> migration.getVersion().getVersion())
            .toList();
    }

    String migrateTo(String version) {
        return DatabaseMigration.migrate(dialect, flyway().target(version).load()).schemaVersion();
    }

    private FluentConfiguration flyway() {
        return DatabaseMigration.configure(dialect, connections, MigrationSteps.class.getClassLoader());
    }
}

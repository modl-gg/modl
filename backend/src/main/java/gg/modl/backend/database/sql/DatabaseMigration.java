package gg.modl.backend.database.sql;

import gg.modl.backend.database.DatabaseDialect;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationState;
import org.flywaydb.core.api.callback.Callback;
import org.flywaydb.core.api.configuration.FluentConfiguration;

public final class DatabaseMigration {
    static final String UNFINISHED_RESTORE =
        "The database holds an unfinished restore or copy. Run restore again before starting modl.";

    private final int appliedMigrations;
    private final String schemaVersion;

    private DatabaseMigration(int appliedMigrations, String schemaVersion) {
        this.appliedMigrations = appliedMigrations;
        this.schemaVersion = schemaVersion;
    }

    public static FluentConfiguration configure(DatabaseDialect dialect, DataSource migrationConnections, ClassLoader classLoader) {
        FluentConfiguration configuration = Flyway.configure(classLoader)
            .dataSource(migrationConnections)
            .locations("classpath:" + dialect.migrationLocation())
            .table(ModlSchema.HISTORY_TABLE)
            .baselineOnMigrate(true)
            .baselineVersion(ModlSchema.BEFORE_FIRST_MIGRATION)
            .failOnMissingLocations(true)
            .cleanDisabled(true)
            .validateMigrationNaming(true);
        if (dialect == DatabaseDialect.SQLITE) {
            configuration.callbacks(new Callback[] {new SqliteForeignKeyCheckCallback()});
        }
        return configuration;
    }

    public static DatabaseMigration migrate(DatabaseDialect dialect, Flyway flyway) {
        requireFinishedRestore(dialect, flyway.getConfiguration().getDataSource());
        flyway.migrate();
        List<MigrationInfo> applied = Arrays.stream(flyway.info().applied())
            .filter(migration -> migration.getState() != MigrationState.BASELINE)
            .toList();
        if (applied.isEmpty()) {
            throw new IllegalStateException("No database migration was found in "
                + List.of(flyway.getConfiguration().getLocations()) + "; the server jar is incomplete");
        }
        MigrationInfo current = flyway.info().current();
        return new DatabaseMigration(applied.size(), current == null ? "none" : current.getVersion().getVersion());
    }

    private static void requireFinishedRestore(DatabaseDialect dialect, DataSource connections) {
        try (Connection connection = connections.getConnection()) {
            Set<String> tables = SchemaCatalog.allTableNames(connection, dialect);
            if (tables.contains(ModlSchema.UNFINISHED_TABLE) && !tables.contains(ModlSchema.HISTORY_TABLE)) {
                throw new IllegalStateException(UNFINISHED_RESTORE);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("The database tables could not be read", e);
        }
    }

    public int appliedMigrations() {
        return appliedMigrations;
    }

    public String schemaVersion() {
        return schemaVersion;
    }
}

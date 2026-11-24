package gg.modl.backend.database.sql;

import static org.assertj.core.api.Assertions.assertThat;

import gg.modl.backend.database.DataDirectoryProperties;
import gg.modl.backend.database.WriteAttemptHook;
import gg.modl.backend.database.jooq.DefaultSchema;
import gg.modl.backend.support.sql.DialectTarget;
import gg.modl.backend.support.sql.SqlContractTest;
import gg.modl.backend.support.sql.SqlTestDatabase;
import gg.modl.backend.support.sql.TestDialects;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.List;
import org.jooq.Table;
import org.junit.jupiter.api.io.TempDir;

class ForeignTablesInstallContractTest {
    private static final String HISTORY_VERSIONS = "SELECT version FROM " + ModlSchema.HISTORY_TABLE + " ORDER BY installed_rank";

    @TempDir
    Path directory;

    private SqlDatabaseProperties database;
    private DataDirectoryProperties dataDirectory;
    private SharedDatabase shared;

    @SqlContractTest
    void aFreshInstallBesideOtherSoftwaresTablesCreatesEveryModlTableAndLeavesTheirsAlone(DialectTarget target) throws Exception {
        SqlDatabaseProperties emptyDatabase = TestDialects.freshDatabase(target);
        DataDirectoryProperties emptyDirectory = new DataDirectoryProperties(directory.resolve("alone").toString());
        MigrationOutcome alone = start(target, emptyDatabase, emptyDirectory);
        List<String> aloneHistory = new SharedDatabase(emptyDatabase, emptyDirectory).query(HISTORY_VERSIONS);
        useBesideOtherSoftware(target);

        MigrationOutcome beside = start(target, database, dataDirectory);

        assertThat(beside).isEqualTo(alone);
        for (Table<?> table : DefaultSchema.DEFAULT_SCHEMA.getTables()) {
            assertThat(shared.hasTable(table.getName())).as(table.getName()).isTrue();
        }
        assertThat(aloneHistory).doesNotContain(ModlSchema.BEFORE_FIRST_MIGRATION);
        List<String> besideHistory = shared.query(HISTORY_VERSIONS);
        assertThat(besideHistory.getFirst()).isEqualTo(ModlSchema.BEFORE_FIRST_MIGRATION);
        assertThat(besideHistory.subList(1, besideHistory.size())).isEqualTo(aloneHistory);
        assertOtherSoftwareUnchanged();
    }

    @SqlContractTest
    void aRestartBesideOtherSoftwaresTablesAppliesNothing(DialectTarget target) throws Exception {
        useBesideOtherSoftware(target);
        MigrationOutcome first = start(target, database, dataDirectory);
        List<String> history = shared.query(HISTORY_VERSIONS);

        MigrationOutcome restarted = start(target, database, dataDirectory);

        assertThat(restarted).isEqualTo(first);
        assertThat(shared.query(HISTORY_VERSIONS)).isEqualTo(history);
        assertOtherSoftwareUnchanged();
    }

    @SqlContractTest
    void anUpgradeOneVersionAtATimeBesideOtherSoftwaresTablesKeepsThem(DialectTarget target) throws Exception {
        useBesideOtherSoftware(target);
        MigrationSteps steps = MigrationSteps.on(target, database, dataDirectory);
        List<String> versions = steps.pendingVersions();
        assertThat(versions).isNotEmpty();

        for (String version : versions) {
            assertThat(steps.migrateTo(version)).isEqualTo(version);
            assertOtherSoftwareUnchanged();
        }

        assertThat(start(target, database, dataDirectory)).isEqualTo(new MigrationOutcome(versions.getLast(), versions.size()));
        assertOtherSoftwareUnchanged();
    }

    private void useBesideOtherSoftware(DialectTarget target) throws Exception {
        database = TestDialects.freshDatabase(target);
        dataDirectory = new DataDirectoryProperties(directory.resolve("beside").toString());
        shared = new SharedDatabase(database, dataDirectory);
        Files.createDirectories(dataDirectory.database());
        shared.addOtherPluginsTable();
        shared.addLookalikeTables();
    }

    private static MigrationOutcome start(DialectTarget target, SqlDatabaseProperties into, DataDirectoryProperties dataDirectory) {
        try (SqlTestDatabase running = SqlTestDatabase.start(target, dataDirectory.root(), into, WriteAttemptHook.NONE)) {
            DatabaseMigration migration = running.bean(DatabaseMigration.class);
            return new MigrationOutcome(migration.schemaVersion(), migration.appliedMigrations());
        }
    }

    private void assertOtherSoftwareUnchanged() throws SQLException {
        assertThat(shared.otherPluginsRows()).isEqualTo(SharedDatabase.OTHER_ROWS);
        assertThat(shared.lookalikeRows()).isEqualTo(SharedDatabase.LOOKALIKE_ROWS);
    }

    private record MigrationOutcome(String schemaVersion, int appliedMigrations) {
    }
}

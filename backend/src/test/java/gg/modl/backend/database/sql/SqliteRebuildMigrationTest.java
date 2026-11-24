package gg.modl.backend.database.sql;

import static gg.modl.backend.database.jooq.Tables.PLAYERS;
import static gg.modl.backend.database.jooq.Tables.PLAYER_IPS;
import static gg.modl.backend.database.jooq.Tables.PLAYER_LINKS;
import static gg.modl.backend.database.jooq.Tables.PLAYER_NOTES;
import static gg.modl.backend.database.jooq.Tables.PUNISHMENTS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import gg.modl.backend.database.DatabaseDialect;
import gg.modl.backend.infrastructure.logging.OperatorNotice;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.api.callback.Callback;
import org.flywaydb.core.api.callback.Context;
import org.flywaydb.core.api.callback.Event;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.jooq.Table;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SqliteRebuildMigrationTest {
    private static final String REBUILD = "classpath:db/test-migration/sqlite-rebuild";
    private static final String DANGLING_REBUILD = "classpath:db/test-migration/sqlite-rebuild-dangling";
    private static final String BASELINE_VERSION = "1";
    private static final String REBUILT_VERSION = "1.1";
    private static final String REBUILT_CHECK = "CHECK (total_playtime_seconds >= 0)";
    private static final List<String> PLAYER_TABLES = Stream.of(PLAYERS, PUNISHMENTS, PLAYER_IPS, PLAYER_LINKS, PLAYER_NOTES)
        .map(Table::getName)
        .toList();
    private static final String REBUILT_TABLE = PLAYERS.getName() + "_rebuilt";

    @TempDir
    Path dataDirectory;

    private DataSource migrationConnections;
    private TransactionProbe probe;

    @BeforeEach
    void prepareBaselineWithPlayers() throws SQLException, IOException {
        Path databaseDirectory = Files.createDirectories(dataDirectory.resolve("database"));
        Path temporary = dataDirectory.resolve("tmp").resolve("sqlite");
        migrationConnections = SqliteDatabaseConnections.prepare(databaseDirectory, temporary, temporary, new ConnectionTracker(),
            new OperatorNotice()).migration();
        probe = new TransactionProbe();
        flyway(REBUILD).target(BASELINE_VERSION).load().migrate();
        seedPlayers();
    }

    @Test
    void aTwelveStepRebuildOfPlayersKeepsEveryChildRowAndPassesTheForeignKeyCheck() throws SQLException {
        Map<String, Long> before = rowCounts();

        DatabaseMigration migration = DatabaseMigration.migrate(DatabaseDialect.SQLITE, flyway(REBUILD).target(REBUILT_VERSION).load());

        assertEquals(REBUILT_VERSION, migration.schemaVersion());
        assertEquals(before, rowCounts());
        assertEquals(List.of(), foreignKeyViolations());
        assertTrue(playersDefinition().contains(REBUILT_CHECK), playersDefinition());
    }

    @Test
    void aRebuildThatLeavesADanglingReferenceFailsAndIsRolledBack() throws SQLException {
        Map<String, Long> before = rowCounts();
        Flyway flyway = flyway(DANGLING_REBUILD).target(REBUILT_VERSION).load();

        FlywayException failure = assertThrows(FlywayException.class, flyway::migrate);

        assertTrue(CauseChain.messages(failure).contains("refers to a missing row in " + PLAYERS.getName()), CauseChain.messages(failure));
        assertEquals(BASELINE_VERSION, flyway.info().current().getVersion().getVersion());
        assertEquals(before, rowCounts());
        assertFalse(playersDefinition().contains(REBUILT_CHECK), playersDefinition());
        assertFalse(tableExists(REBUILT_TABLE));
        assertEquals(List.of(), foreignKeyViolations());
    }

    @Test
    void callbacksAfterEachMigrationRunInsideTheMigrationTransaction() {
        flyway(REBUILD).target(REBUILT_VERSION).load().migrate();

        assertEquals(List.of(false), probe.autoCommitAfterEachMigrate());
    }

    private FluentConfiguration flyway(String testLocation) {
        FluentConfiguration configuration = DatabaseMigration.configure(DatabaseDialect.SQLITE, migrationConnections,
            SqliteRebuildMigrationTest.class.getClassLoader());
        Callback[] callbacks = Stream.concat(Stream.of(probe), Stream.of(configuration.getCallbacks())).toArray(Callback[]::new);
        return configuration
            .locations("classpath:" + DatabaseDialect.SQLITE.migrationLocation(), testLocation)
            .callbacks(callbacks);
    }

    private void seedPlayers() throws SQLException {
        try (Connection connection = migrationConnections.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA foreign_keys = ON");
            for (int player = 1; player <= 3; player++) {
                String id = "'player-" + player + "'";
                statement.execute("INSERT INTO modl_players (id, minecraft_uuid, username, username_lower, created_at) VALUES ("
                    + id + ", 'uuid-" + player + "', 'Player" + player + "', 'player" + player + "', 1000)");
                statement.execute("INSERT INTO modl_punishments (id, player_id, type_ordinal, issued_at, enforcement_category, "
                    + "active_until_at) VALUES ('punishment-" + player + "', " + id + ", 1, 2000, 'BAN', 9000)");
                statement.execute("INSERT INTO modl_player_ips (player_id, ip, first_seen_at, last_seen_at) VALUES (" + id
                    + ", '10.0.0." + player + "', 3000, 4000)");
                statement.execute("INSERT INTO modl_player_notes (player_id, seq, id, body, created_at) VALUES (" + id
                    + ", 1, 'note-" + player + "', 'watch', 5000)");
            }
            statement.execute("INSERT INTO modl_player_links (player_id, linked_player_id, linked_at) VALUES ('player-1', 'player-2', 6000)");
            statement.execute("INSERT INTO modl_player_links (player_id, linked_player_id, linked_at) VALUES ('player-2', 'player-3', 6000)");
        }
    }

    private Map<String, Long> rowCounts() throws SQLException {
        Map<String, Long> counts = new LinkedHashMap<>();
        try (Connection connection = migrationConnections.getConnection(); Statement statement = connection.createStatement()) {
            for (String table : PLAYER_TABLES) {
                try (ResultSet result = statement.executeQuery("SELECT count(*) FROM " + table)) {
                    counts.put(table, result.next() ? result.getLong(1) : -1);
                }
            }
        }
        return counts;
    }

    private List<String> foreignKeyViolations() throws SQLException {
        List<String> violations = new ArrayList<>();
        try (Connection connection = migrationConnections.getConnection(); Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("PRAGMA foreign_key_check")) {
            while (result.next()) {
                violations.add(result.getString(1) + " -> " + result.getString(3));
            }
        }
        return violations;
    }

    private String playersDefinition() throws SQLException {
        return schemaSql(PLAYERS.getName());
    }

    private boolean tableExists(String table) throws SQLException {
        return !schemaSql(table).isEmpty();
    }

    private String schemaSql(String table) throws SQLException {
        try (Connection connection = migrationConnections.getConnection();
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SELECT sql FROM sqlite_schema WHERE type = 'table' AND name = '" + table + "'")) {
            return result.next() ? result.getString(1) : "";
        }
    }

    private static final class TransactionProbe implements Callback {
        private final List<Boolean> autoCommitAfterEachMigrate = new ArrayList<>();

        @Override
        public boolean supports(Event event, Context context) {
            return event == Event.AFTER_EACH_MIGRATE;
        }

        @Override
        public boolean canHandleInTransaction(Event event, Context context) {
            return true;
        }

        @Override
        public void handle(Event event, Context context) {
            if (context.getMigrationInfo() == null || !REBUILT_VERSION.equals(context.getMigrationInfo().getVersion().getVersion())) {
                return;
            }
            try {
                autoCommitAfterEachMigrate.add(context.getConnection().getAutoCommit());
            } catch (SQLException e) {
                throw new FlywayException(e);
            }
        }

        @Override
        public String getCallbackName() {
            return "transaction-probe";
        }

        List<Boolean> autoCommitAfterEachMigrate() {
            return List.copyOf(autoCommitAfterEachMigrate);
        }
    }
}

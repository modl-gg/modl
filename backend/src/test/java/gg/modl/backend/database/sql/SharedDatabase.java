package gg.modl.backend.database.sql;

import gg.modl.backend.database.DataDirectoryProperties;
import gg.modl.backend.database.DatabaseDialect;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

final class SharedDatabase {
    static final String OTHER_TABLE = "otherplugin_balances";
    static final List<String> OTHER_ROWS = List.of("1|12.50|alice", "2|99.99|bob");
    static final String UNPREFIXED_LOOKALIKE = "players";
    static final String PREFIXED_LOOKALIKE = "modl_players_archive";
    static final String OTHER_FLYWAY_HISTORY = "flyway_schema_history";
    static final Map<String, List<String>> LOOKALIKE_ROWS = Map.of(
        UNPREFIXED_LOOKALIKE, List.of("Notch"),
        PREFIXED_LOOKALIKE, List.of("archived"),
        OTHER_FLYWAY_HISTORY, List.of("1|4242"));

    private final SqlDatabaseProperties database;
    private final DataDirectoryProperties dataDirectory;

    SharedDatabase(SqlDatabaseProperties database, DataDirectoryProperties dataDirectory) {
        this.database = database;
        this.dataDirectory = dataDirectory;
    }

    void addOtherPluginsTable() throws SQLException {
        execute("CREATE TABLE " + OTHER_TABLE + " (id INTEGER NOT NULL PRIMARY KEY, amount DECIMAL(10,2) NOT NULL, owner VARCHAR(32))",
            "INSERT INTO " + OTHER_TABLE + " VALUES (1, 12.50, 'alice'), (2, 99.99, 'bob')");
    }

    void addLookalikeTables() throws SQLException {
        execute("CREATE TABLE " + UNPREFIXED_LOOKALIKE + " (name VARCHAR(16))",
            "INSERT INTO " + UNPREFIXED_LOOKALIKE + " VALUES ('Notch')",
            "CREATE TABLE " + PREFIXED_LOOKALIKE + " (id VARCHAR(36))",
            "INSERT INTO " + PREFIXED_LOOKALIKE + " VALUES ('archived')",
            "CREATE TABLE " + OTHER_FLYWAY_HISTORY + " (installed_rank INTEGER NOT NULL PRIMARY KEY, version VARCHAR(50),"
                + " checksum INTEGER, success BOOLEAN NOT NULL)",
            "INSERT INTO " + OTHER_FLYWAY_HISTORY + " VALUES (1, '1', 4242, TRUE)");
    }

    Map<String, List<String>> lookalikeRows() throws SQLException {
        return Map.of(
            UNPREFIXED_LOOKALIKE, query("SELECT name FROM " + UNPREFIXED_LOOKALIKE),
            PREFIXED_LOOKALIKE, query("SELECT id FROM " + PREFIXED_LOOKALIKE),
            OTHER_FLYWAY_HISTORY, query("SELECT version, checksum FROM " + OTHER_FLYWAY_HISTORY));
    }

    List<String> otherPluginsRows() throws SQLException {
        return query("SELECT id, amount, owner FROM " + OTHER_TABLE + " ORDER BY id").stream()
            .map(row -> row.replace("|12.5|", "|12.50|"))
            .toList();
    }

    String playerIdColumn() {
        return switch (database.dialect()) {
            case SQLITE -> "VARCHAR(36)";
            case MYSQL, MARIADB -> "VARCHAR(36) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin";
            case POSTGRESQL -> "VARCHAR(36) COLLATE \"C\"";
        };
    }

    void execute(String... statements) throws SQLException {
        try (Connection connection = connect(); Statement statement = connection.createStatement()) {
            for (String sql : statements) {
                statement.execute(sql);
            }
        }
    }

    List<String> query(String sql) throws SQLException {
        List<String> rows = new ArrayList<>();
        try (Connection connection = connect(); Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(sql)) {
            int columns = result.getMetaData().getColumnCount();
            while (result.next()) {
                List<String> values = new ArrayList<>();
                for (int column = 1; column <= columns; column++) {
                    values.add(result.getString(column));
                }
                rows.add(String.join("|", values));
            }
        }
        return rows;
    }

    boolean hasTable(String table) throws SQLException {
        try (Connection connection = connect()) {
            return SchemaCatalog.allTableNames(connection, database.dialect()).contains(table);
        }
    }

    private Connection connect() throws SQLException {
        DatabaseDialect dialect = database.dialect();
        return dialect.isExternal()
            ? ExternalSessions.migration(DriverDataSource.of(database), dialect).getConnection()
            : SqlitePragmas.open(dataDirectory.database().resolve(SqliteConnections.DATABASE_FILE_NAME));
    }
}

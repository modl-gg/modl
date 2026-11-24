package gg.modl.backend.database.sql;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

final class CrashedDatabase {
    private CrashedDatabase() {
    }

    static void assertSound(Path dataDirectory) throws SQLException {
        Path file = dataDirectory.resolve("database").resolve(SqliteConnections.DATABASE_FILE_NAME);
        try (Connection connection = SqlitePragmas.open(file); Statement statement = connection.createStatement()) {
            assertThat(SqlitePragmas.text(connection, "integrity_check")).isEqualTo("ok");
            try (ResultSet violations = statement.executeQuery("PRAGMA foreign_key_check")) {
                assertThat(violations.next()).as("a dangling foreign key").isFalse();
            }
        }
    }
}

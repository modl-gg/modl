package gg.modl.backend.database.sql;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

final class SqliteFileRows {
    private SqliteFileRows() {
    }

    static void addTicketTheSchemaRejects(Path file) throws SQLException {
        try (Connection connection = SqlitePragmas.open(file); Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA ignore_check_constraints = ON");
            statement.executeUpdate("INSERT INTO modl_tickets (id, type, status, public_access_key, created_at, updated_at) "
                + "VALUES ('rejected', 'support', 'not-a-status', 'key', 0, 0)");
        }
    }
}

package gg.modl.backend.database.sql;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import javax.sql.DataSource;

final class BulkPayload {
    private static final int MEBIBYTE = 1024 * 1024;

    private BulkPayload() {
    }

    static void fill(DataSource writer, int mebibytes) throws SQLException {
        try (Connection connection = writer.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE bulk_payload (id INTEGER PRIMARY KEY, payload BLOB NOT NULL)");
            statement.execute("WITH RECURSIVE n(x) AS (SELECT 1 UNION ALL SELECT x + 1 FROM n WHERE x < " + mebibytes + ") "
                + "INSERT INTO bulk_payload SELECT x, zeroblob(" + MEBIBYTE + ") FROM n");
            statement.execute("PRAGMA wal_checkpoint(TRUNCATE)");
        }
    }
}

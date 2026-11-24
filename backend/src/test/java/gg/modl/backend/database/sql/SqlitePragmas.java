package gg.modl.backend.database.sql;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import org.sqlite.SQLiteDataSource;

final class SqlitePragmas {
    private SqlitePragmas() {
    }

    static String text(Connection connection, String pragma) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("PRAGMA " + pragma)) {
            if (!result.next()) {
                throw new IllegalStateException("PRAGMA " + pragma + " returned no row");
            }
            return result.getString(1);
        }
    }

    static long number(Connection connection, String pragma) throws SQLException {
        return Long.parseLong(text(connection, pragma));
    }

    static Connection open(Path databaseFile) throws SQLException {
        SQLiteDataSource dataSource = new SQLiteDataSource();
        dataSource.setUrl("jdbc:sqlite:" + databaseFile);
        return dataSource.getConnection();
    }
}

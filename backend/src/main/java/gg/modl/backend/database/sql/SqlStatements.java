package gg.modl.backend.database.sql;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

final class SqlStatements {
    private final RunningStatements running;

    SqlStatements(RunningStatements running) {
        this.running = running;
    }

    void update(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            running.register(statement);
            try {
                statement.executeUpdate(sql);
            } finally {
                running.deregister(statement);
            }
        }
    }

    void execute(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            running.register(statement);
            try {
                statement.execute(sql);
            } finally {
                running.deregister(statement);
            }
        }
    }

    List<List<String>> rows(Connection connection, String sql) throws SQLException {
        List<List<String>> rows = new ArrayList<>();
        try (Statement statement = connection.createStatement()) {
            running.register(statement);
            try (ResultSet result = statement.executeQuery(sql)) {
                int columns = result.getMetaData().getColumnCount();
                while (result.next()) {
                    List<String> row = new ArrayList<>(columns);
                    for (int column = 1; column <= columns; column++) {
                        row.add(result.getString(column));
                    }
                    rows.add(row);
                }
            } finally {
                running.deregister(statement);
            }
        }
        return rows;
    }

    long number(Connection connection, String sql) throws SQLException {
        return Long.parseLong(text(connection, sql));
    }

    String text(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            running.register(statement);
            try (ResultSet result = statement.executeQuery(sql)) {
                if (!result.next()) {
                    throw new SQLException("The database returned no row for " + sql);
                }
                return result.getString(1);
            } finally {
                running.deregister(statement);
            }
        }
    }
}

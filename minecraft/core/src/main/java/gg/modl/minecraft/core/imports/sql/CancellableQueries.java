package gg.modl.minecraft.core.imports.sql;

import org.jspecify.annotations.Nullable;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

public final class CancellableQueries {
    private static final String CANCELLED = "The import query was cancelled";

    private volatile @Nullable Statement running;
    private volatile boolean cancelled;

    public void read(PreparedStatement statement, SqlSession.Binder binder, SqlSession.RowReader reader) throws SQLException {
        binder.bind(statement);
        running = statement;
        try {
            requireActive();
            try (ResultSet row = statement.executeQuery()) {
                while (row.next()) {
                    requireActive();
                    reader.read(row);
                }
            }
        } finally {
            running = null;
        }
    }

    public void execute(Statement statement, String sql) throws SQLException {
        running = statement;
        try {
            requireActive();
            statement.execute(sql);
        } finally {
            running = null;
        }
    }

    public ResultSet open(PreparedStatement statement) throws SQLException {
        running = statement;
        try {
            requireActive();
            return statement.executeQuery();
        } catch (SQLException | RuntimeException failure) {
            running = null;
            throw failure;
        }
    }

    public void finished() {
        running = null;
    }

    public void requireActive() throws SQLException {
        if (cancelled) throw new SQLException(CANCELLED);
    }

    public boolean isCancelled() {
        return cancelled;
    }

    public void cancel() {
        cancelled = true;
        Statement statement = running;
        if (statement == null) return;
        try {
            statement.cancel();
        } catch (SQLException | RuntimeException alreadyDone) {
            cancelled = true;
        }
    }
}

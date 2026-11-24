package gg.modl.backend.database.sql;

import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.logging.Logger;
import javax.sql.DataSource;

final class TrackedDataSource implements DataSource {
    private final DataSource target;
    private final ConnectionTracker tracker;
    private final ConnectionRole role;

    TrackedDataSource(DataSource target, ConnectionTracker tracker, ConnectionRole role) {
        this.target = target;
        this.tracker = tracker;
        this.role = role;
    }

    @Override
    public Connection getConnection() throws SQLException {
        return tracker.tracked(target.getConnection(), role);
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        return tracker.tracked(target.getConnection(username, password), role);
    }

    @Override
    public PrintWriter getLogWriter() throws SQLException {
        return target.getLogWriter();
    }

    @Override
    public void setLogWriter(PrintWriter out) throws SQLException {
        target.setLogWriter(out);
    }

    @Override
    public void setLoginTimeout(int seconds) throws SQLException {
        target.setLoginTimeout(seconds);
    }

    @Override
    public int getLoginTimeout() throws SQLException {
        return target.getLoginTimeout();
    }

    @Override
    public Logger getParentLogger() throws SQLFeatureNotSupportedException {
        return target.getParentLogger();
    }

    @Override
    public <T> T unwrap(Class<T> type) throws SQLException {
        return type.isInstance(this) ? type.cast(this) : target.unwrap(type);
    }

    @Override
    public boolean isWrapperFor(Class<?> type) throws SQLException {
        return type.isInstance(this) || target.isWrapperFor(type);
    }
}

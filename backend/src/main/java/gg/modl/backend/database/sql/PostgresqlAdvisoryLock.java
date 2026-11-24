package gg.modl.backend.database.sql;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Optional;

final class PostgresqlAdvisoryLock implements AdvisoryLockStatements {
    private static final long LOW_32_BITS = 0xffffffffL;
    private static final String SESSION_START_MICROS = "CAST(EXTRACT(EPOCH FROM a.backend_start) * 1000000 AS BIGINT)";
    private static final String GRANTED_LOCK = "l.locktype = 'advisory' AND l.granted "
        + "AND l.database = (SELECT oid FROM pg_database WHERE datname = current_database()) "
        + "AND CAST(l.classid AS BIGINT) = ? AND CAST(l.objid AS BIGINT) = ? AND l.objsubid = 1";

    private final long key;

    private PostgresqlAdvisoryLock(long key) {
        this.key = key;
    }

    static PostgresqlAdvisoryLock keyed(long key) {
        return new PostgresqlAdvisoryLock(key);
    }

    @Override
    public void prepareSession(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("SET idle_session_timeout = '" + SESSION_TIMEOUT_SECONDS + "s'");
        }
    }

    @Override
    public LockHolder self(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SELECT a.pid, " + SESSION_START_MICROS
                 + " FROM pg_stat_activity a WHERE a.pid = pg_backend_pid()")) {
            if (!result.next()) {
                throw new SQLException("The database did not report its backend process");
            }
            return new LockHolder(result.getLong(1), result.getLong(2));
        }
    }

    @Override
    public boolean tryLock(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT pg_try_advisory_lock(?)")) {
            statement.setLong(1, key);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() && result.getBoolean(1);
            }
        }
    }

    @Override
    public boolean owns(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT EXISTS (SELECT 1 FROM pg_locks l WHERE "
            + GRANTED_LOCK + " AND l.pid = pg_backend_pid())")) {
            bindKey(statement);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() && result.getBoolean(1);
            }
        }
    }

    @Override
    public Optional<LockHolder> holder(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT a.pid, " + SESSION_START_MICROS
            + " FROM pg_locks l JOIN pg_stat_activity a ON a.pid = l.pid WHERE " + GRANTED_LOCK)) {
            bindKey(statement);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? Optional.of(new LockHolder(result.getLong(1), result.getLong(2))) : Optional.empty();
            }
        }
    }

    @Override
    public void terminate(Connection connection, LockHolder holder) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT pg_terminate_backend(?)")) {
            statement.setInt(1, Math.toIntExact(holder.sessionId()));
            statement.executeQuery().close();
        }
    }

    @Override
    public void unlock(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT pg_advisory_unlock(?)")) {
            statement.setLong(1, key);
            statement.executeQuery().close();
        }
    }

    private void bindKey(PreparedStatement statement) throws SQLException {
        statement.setLong(1, key >>> Integer.SIZE);
        statement.setLong(2, key & LOW_32_BITS);
    }
}

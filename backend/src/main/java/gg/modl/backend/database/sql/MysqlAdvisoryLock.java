package gg.modl.backend.database.sql;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HexFormat;
import java.util.Optional;

final class MysqlAdvisoryLock implements AdvisoryLockStatements {
    private static final String LOCK_PREFIX = "modl:";
    private static final int NAME_HEX_CHARACTERS = 32;

    private final String lockName;

    private MysqlAdvisoryLock(String lockName) {
        this.lockName = lockName;
    }

    static MysqlAdvisoryLock named(byte[] databaseDigest) {
        return new MysqlAdvisoryLock(LOCK_PREFIX + HexFormat.of().formatHex(databaseDigest).substring(0, NAME_HEX_CHARACTERS));
    }

    @Override
    public void prepareSession(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("SET SESSION wait_timeout = " + SESSION_TIMEOUT.toSeconds());
        }
    }

    @Override
    public LockHolder self(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery("SELECT CONNECTION_ID()")) {
            if (!result.next()) {
                throw new SQLException("The database did not report its connection id");
            }
            return new LockHolder(result.getLong(1), 0);
        }
    }

    @Override
    public boolean tryLock(Connection connection) throws SQLException {
        return queryFlag(connection, "SELECT GET_LOCK(?, 0)");
    }

    @Override
    public boolean owns(Connection connection) throws SQLException {
        return queryFlag(connection, "SELECT IS_USED_LOCK(?) = CONNECTION_ID()");
    }

    @Override
    public Optional<LockHolder> holder(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT IS_USED_LOCK(?)")) {
            statement.setString(1, lockName);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    return Optional.empty();
                }
                long holder = result.getLong(1);
                return result.wasNull() ? Optional.empty() : Optional.of(new LockHolder(holder, 0));
            }
        }
    }

    @Override
    public void terminate(Connection connection, LockHolder holder) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("KILL " + holder.sessionId());
        }
    }

    @Override
    public void unlock(Connection connection) throws SQLException {
        queryFlag(connection, "SELECT RELEASE_LOCK(?)");
    }

    private boolean queryFlag(Connection connection, String sql) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, lockName);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() && result.getBoolean(1);
            }
        }
    }
}

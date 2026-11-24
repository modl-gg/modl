package gg.modl.backend.database.sql;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;

@Slf4j
final class LockSession implements AutoCloseable {
    private final Connection connection;
    private final AdvisoryLockStatements statements;
    private final LockHolder self;

    LockSession(Connection connection, AdvisoryLockStatements statements, LockHolder self) {
        this.connection = connection;
        this.statements = statements;
        this.self = self;
    }

    LockHolder self() {
        return self;
    }

    boolean tryLock() throws SQLException {
        return statements.tryLock(connection);
    }

    boolean owns() {
        try {
            return statements.owns(connection);
        } catch (SQLException e) {
            log.debug("The database lock connection failed its heartbeat", e);
            return false;
        }
    }

    Optional<LockHolder> holder() throws SQLException {
        return statements.holder(connection);
    }

    void terminate(LockHolder holder) throws SQLException {
        statements.terminate(connection, holder);
    }

    void unlock() throws SQLException {
        statements.unlock(connection);
    }

    @Override
    public void close() {
        closeQuietly(connection);
    }

    static void closeQuietly(Connection connection) {
        try {
            connection.close();
        } catch (SQLException e) {
            log.debug("Could not close the database lock connection", e);
        }
    }
}

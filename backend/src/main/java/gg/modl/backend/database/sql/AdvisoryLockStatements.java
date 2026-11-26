package gg.modl.backend.database.sql;

import gg.modl.backend.database.DatabaseDialect;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Optional;

interface AdvisoryLockStatements {
    Duration SESSION_TIMEOUT = Duration.ofMinutes(2);

    static AdvisoryLockStatements of(DatabaseDialect dialect, Connection connection) throws SQLException {
        String catalog = String.valueOf(connection.getCatalog());
        if (dialect.isMysqlFamily()) {
            return MysqlAdvisoryLock.named(sha256(catalog));
        }
        return PostgresqlAdvisoryLock.keyed(ByteBuffer.wrap(sha256(catalog + "." + connection.getSchema())).getLong());
    }

    void prepareSession(Connection connection) throws SQLException;

    LockHolder self(Connection connection) throws SQLException;

    boolean tryLock(Connection connection) throws SQLException;

    boolean owns(Connection connection) throws SQLException;

    Optional<LockHolder> holder(Connection connection) throws SQLException;

    void terminate(Connection connection, LockHolder holder) throws SQLException;

    void unlock(Connection connection) throws SQLException;

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }
}

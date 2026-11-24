package gg.modl.backend.database.sql;

import gg.modl.backend.database.DatabaseDialect;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.HexFormat;

final class ExternalLockSessions implements AutoCloseable {
    private static final Duration POLL_INTERVAL = Duration.ofMillis(100);
    private static final Duration FREE_TIMEOUT = Duration.ofSeconds(20);
    private static final String POSTGRESQL_ADVISORY_LOCKS = "FROM pg_locks WHERE locktype = 'advisory' AND granted "
        + "AND database = (SELECT oid FROM pg_database WHERE datname = current_database())";

    private final DatabaseDialect dialect;
    private final Connection admin;
    private final String mysqlLockName;

    private ExternalLockSessions(DatabaseDialect dialect, Connection admin, String mysqlLockName) {
        this.dialect = dialect;
        this.admin = admin;
        this.mysqlLockName = mysqlLockName;
    }

    static ExternalLockSessions open(SqlDatabaseProperties database) throws SQLException {
        Connection admin = DriverDataSource.of(database).getConnection();
        return new ExternalLockSessions(database.dialect(), admin, "modl:" + sha256Hex(admin.getCatalog()).substring(0, 32));
    }

    void killHolder() throws SQLException {
        if (dialect.isMysqlFamily()) {
            long holder = mysqlHolder();
            try (Statement statement = admin.createStatement()) {
                statement.execute("KILL " + holder);
            }
        } else {
            try (Statement statement = admin.createStatement()) {
                statement.execute("SELECT pg_terminate_backend(pid) " + POSTGRESQL_ADVISORY_LOCKS);
            }
        }
    }

    boolean isHeld() throws SQLException {
        if (dialect.isMysqlFamily()) {
            return mysqlHolder() != 0;
        }
        try (Statement statement = admin.createStatement();
             ResultSet result = statement.executeQuery("SELECT count(*) " + POSTGRESQL_ADVISORY_LOCKS)) {
            return result.next() && result.getLong(1) > 0;
        }
    }

    void awaitFree() throws SQLException, InterruptedException {
        long deadline = System.nanoTime() + FREE_TIMEOUT.toNanos();
        while (isHeld()) {
            if (System.nanoTime() >= deadline) {
                throw new IllegalStateException("The killed lock session still holds the lock");
            }
            Thread.sleep(POLL_INTERVAL);
        }
    }

    @Override
    public void close() throws SQLException {
        admin.close();
    }

    private long mysqlHolder() throws SQLException {
        try (PreparedStatement statement = admin.prepareStatement("SELECT IS_USED_LOCK(?)")) {
            statement.setString(1, mysqlLockName);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? result.getLong(1) : 0;
            }
        }
    }

    private static String sha256Hex(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}

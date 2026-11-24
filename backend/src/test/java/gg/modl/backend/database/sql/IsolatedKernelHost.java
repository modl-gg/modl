package gg.modl.backend.database.sql;

import static gg.modl.backend.database.jooq.Tables.SETTINGS;

import gg.modl.backend.auth.password.PasswordHasher;
import gg.modl.backend.auth.password.PasswordHashingGate;
import gg.modl.backend.database.WriteAttemptHook;
import gg.modl.backend.database.jooq.tables.records.SettingsRecord;
import gg.modl.backend.support.sql.DialectTarget;
import gg.modl.backend.support.sql.SqlTestDatabase;
import gg.modl.backend.support.sql.TestDialects;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.stream.Stream;

public final class IsolatedKernelHost {
    private SqlTestDatabase database;
    private Connection heldConnection;
    private PasswordHashingGate hashingGate;

    public void start(Path dataDirectory) {
        database = SqlTestDatabase.start(TestDialects.SQLITE, dataDirectory);
    }

    public void startExternal(Path dataDirectory, String dialectName, String url, String username, String password) {
        DialectTarget target = Stream.of(TestDialects.MYSQL, TestDialects.MARIADB, TestDialects.POSTGRESQL, TestDialects.MARIADB_LATEST,
                TestDialects.POSTGRESQL_LATEST)
            .filter(candidate -> candidate.name().equals(dialectName))
            .findFirst()
            .orElseThrow();
        database = SqlTestDatabase.start(target, dataDirectory, new SqlDatabaseProperties(url, username, password, null, null),
            WriteAttemptHook.NONE);
    }

    public void writeSettings(String type) {
        database.unitOfWork().write(() -> database.insertIfAbsent().onPrimaryKey(SETTINGS)
            .insert(new SettingsRecord(type, "{}", 0L, SqlTestDatabase.START)));
    }

    public boolean hasSettings(String type) {
        return database.unitOfWork().read(() -> database.dsl().fetchExists(SETTINGS, SETTINGS.TYPE.eq(type)));
    }

    public void holdConnection() throws SQLException {
        heldConnection = database.bean(ConnectionPools.class).writer().getConnection();
    }

    public void releaseHeldConnection() throws SQLException {
        heldConnection.close();
        heldConnection = null;
    }

    public long registeredDrivers() {
        return DriverManager.drivers()
            .filter(driver -> driver.getClass().getClassLoader() == IsolatedKernelHost.class.getClassLoader())
            .count();
    }

    public boolean hashPassword(String password) {
        hashingGate = new PasswordHashingGate();
        PasswordHasher hasher = new PasswordHasher(hashingGate);
        return hasher.matches(password, hasher.hash(password));
    }

    public void close() throws InterruptedException {
        if (hashingGate != null) {
            hashingGate.destroy();
            hashingGate = null;
        }
        database.close();
        database = null;
    }
}

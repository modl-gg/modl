package gg.modl.backend.database.sql;

import static gg.modl.backend.database.jooq.Tables.SETTINGS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import gg.modl.backend.database.jooq.tables.records.SettingsRecord;
import gg.modl.backend.infrastructure.logging.OperatorNotice;
import gg.modl.backend.support.sql.SqlTestDatabase;
import gg.modl.backend.support.sql.TestDialects;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedConstruction;

class SqliteSettingsTest {
    private static final int CANCELLABLE_BACKUP_MEBIBYTES = 512;
    private static final Duration CANCEL_TIMEOUT = Duration.ofSeconds(5);

    @TempDir
    Path dataDirectory;

    @Test
    void aNewFileUsesIncrementalAutoVacuumAndWal() throws SQLException {
        try (SqlTestDatabase database = start(); Connection writer = pools(database).writer().getConnection()) {
            assertEquals(SqliteDatabaseFile.INCREMENTAL_AUTO_VACUUM, SqlitePragmas.number(writer, "auto_vacuum"));
            assertEquals("wal", SqlitePragmas.text(writer, "journal_mode"));
        }
    }

    @Test
    void writerAndReaderConnectionsAreDurableAndEnforceForeignKeys() throws SQLException {
        try (SqlTestDatabase database = start()) {
            for (DataSource pool : List.of(pools(database).writer(), pools(database).reader())) {
                try (Connection connection = pool.getConnection()) {
                    assertEquals(2, SqlitePragmas.number(connection, "synchronous"));
                    assertEquals(1, SqlitePragmas.number(connection, "foreign_keys"));
                    assertEquals(SqliteConnections.BUSY_TIMEOUT_MILLIS, SqlitePragmas.number(connection, "busy_timeout"));
                    assertEquals(SqliteConnections.JOURNAL_SIZE_LIMIT_BYTES, SqlitePragmas.number(connection, "journal_size_limit"));
                }
            }
        }
    }

    @Test
    void theMigrationConnectionRunsWithForeignKeysOff() throws SQLException {
        try (SqlTestDatabase database = start();
             Connection migration = database.bean(DatabaseConnections.class).migration().getConnection()) {
            assertEquals(0, SqlitePragmas.number(migration, "foreign_keys"));
            assertEquals(2, SqlitePragmas.number(migration, "synchronous"));
            assertEquals(SqliteConnections.BUSY_TIMEOUT_MILLIS, SqlitePragmas.number(migration, "busy_timeout"));
        }
    }

    @Test
    void readerConnectionsRejectWrites() throws SQLException {
        try (SqlTestDatabase database = start(); Connection reader = pools(database).reader().getConnection();
             Statement statement = reader.createStatement()) {
            assertEquals(1, SqlitePragmas.number(reader, "query_only"));
            assertThrows(SQLException.class,
                () -> statement.executeUpdate("INSERT INTO modl_settings (type, data_json, version, updated_at) VALUES ('x', '{}', 0, 0)"));
            assertEquals(0, database.dsl().fetchCount(SETTINGS));
        }
    }

    @Test
    void aSnapshotCopyKeepsIncrementalAutoVacuumAndPassesTheIntegrityCheck() throws SQLException {
        Path copy = dataDirectory.resolve("copy.db");
        try (SqlTestDatabase database = start()) {
            writeGeneralSettings(database);

            database.bean(SnapshotConnections.class).vacuumInto(copy);
        }
        try (Connection connection = SqlitePragmas.open(copy)) {
            assertEquals(SqliteDatabaseFile.INCREMENTAL_AUTO_VACUUM, SqlitePragmas.number(connection, "auto_vacuum"));
            assertEquals("ok", SqlitePragmas.text(connection, "integrity_check"));
            assertEquals(1, rowCount(connection, SETTINGS.getName()));
        }
    }

    @Test
    void theSnapshotStatementFailsOnAReaderConnection() throws SQLException {
        Path copy = dataDirectory.resolve("copy.db");
        try (SqlTestDatabase database = start(); Connection reader = pools(database).reader().getConnection();
             PreparedStatement vacuum = reader.prepareStatement("VACUUM INTO ?")) {
            vacuum.setString(1, copy.toString());

            assertThrows(SQLException.class, vacuum::execute);
        }
    }

    @Test
    void aCancelledSnapshotLeavesNoPartialFile() throws SQLException {
        Path copy = dataDirectory.resolve("copy.db");
        try (SqlTestDatabase database = start()) {
            BulkPayload.fill(pools(database).writer(), CANCELLABLE_BACKUP_MEBIBYTES);
            RunningStatements running = database.bean(RunningStatements.class);
            CompletableFuture<Void> backup = CompletableFuture.runAsync(() -> vacuumInto(database, copy));
            awaitRunning(running, backup);

            running.cancelAll(CANCEL_TIMEOUT);

            CompletionException failure = assertThrows(CompletionException.class, backup::join);
            assertInstanceOf(SQLException.class, failure.getCause().getCause(), CauseChain.messages(failure));
        }
        assertFalse(Files.exists(copy));
    }

    @Test
    void aSnapshotThatCannotCreateItsTargetFailsAndLeavesNoFile() {
        Path copy = dataDirectory.resolve("missing").resolve("copy.db");
        try (SqlTestDatabase database = start()) {
            assertThrows(SQLException.class, () -> database.bean(SnapshotConnections.class).vacuumInto(copy));
        }
        assertFalse(Files.exists(copy));
    }

    @Test
    void aSnapshotRefusedBecauseItsTargetExistsLeavesThatFileUntouched() throws IOException {
        Path existing = Files.writeString(dataDirectory.resolve("existing.db"), "operator data");
        try (SqlTestDatabase database = start()) {
            assertThrows(SQLException.class, () -> database.bean(SnapshotConnections.class).vacuumInto(existing));
        }
        assertEquals("operator data", Files.readString(existing));
    }

    @Test
    void aFileInRollbackJournalModeIsWalAfterTheNextStart() throws SQLException, IOException {
        Path copy = dataDirectory.resolve("copy.db");
        try (SqlTestDatabase database = start()) {
            writeGeneralSettings(database);
            database.bean(SnapshotConnections.class).vacuumInto(copy);
        }
        try (Connection connection = SqlitePragmas.open(copy)) {
            assertEquals("delete", SqlitePragmas.text(connection, "journal_mode"));
        }
        Path restored = dataDirectory.resolve("restored");
        Files.createDirectories(restored.resolve("database"));
        Files.move(copy, restored.resolve("database").resolve(SqliteConnections.DATABASE_FILE_NAME));

        try (MockedConstruction<OperatorNotice> notices = mockConstruction(OperatorNotice.class);
             SqlTestDatabase database = SqlTestDatabase.start(TestDialects.SQLITE, restored);
             Connection writer = pools(database).writer().getConnection()) {
            assertEquals("wal", SqlitePragmas.text(writer, "journal_mode"));
            assertEquals(1, database.dsl().fetchCount(SETTINGS));
            verify(notices.constructed().getFirst(), never()).publish(SqliteDatabaseFile.NOT_INCREMENTAL_NOTICE);
        }
    }

    @Test
    void aPreExistingFileWithoutIncrementalAutoVacuumStartsWithANoticeAndIsNotVacuumed() throws SQLException {
        Path databaseFile = dataDirectory.resolve("database").resolve(SqliteConnections.DATABASE_FILE_NAME);
        createNonIncrementalFile(databaseFile);

        try (MockedConstruction<OperatorNotice> notices = mockConstruction(OperatorNotice.class);
             SqlTestDatabase database = start();
             Connection writer = pools(database).writer().getConnection()) {
            verify(notices.constructed().getFirst()).publish(SqliteDatabaseFile.NOT_INCREMENTAL_NOTICE);
            assertEquals(0, SqlitePragmas.number(writer, "auto_vacuum"));
            assertEquals("wal", SqlitePragmas.text(writer, "journal_mode"));
        }
    }

    private SqlTestDatabase start() {
        return SqlTestDatabase.start(TestDialects.SQLITE, dataDirectory);
    }

    private static ConnectionPools pools(SqlTestDatabase database) {
        return database.bean(ConnectionPools.class);
    }

    private static void writeGeneralSettings(SqlTestDatabase database) {
        database.unitOfWork().write(() -> database.insertIfAbsent().onPrimaryKey(SETTINGS)
            .insert(new SettingsRecord("general", "{}", 0L, SqlTestDatabase.START)));
    }

    private static void vacuumInto(SqlTestDatabase database, Path target) {
        try {
            database.bean(SnapshotConnections.class).vacuumInto(target);
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void awaitRunning(RunningStatements running, CompletableFuture<Void> backup) {
        while (running.size() == 0) {
            if (backup.isDone()) {
                throw new IllegalStateException("The snapshot ended before it could be cancelled");
            }
            Thread.onSpinWait();
        }
    }

    private static long rowCount(Connection connection, String table) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SELECT count(*) FROM " + table)) {
            return result.next() ? result.getLong(1) : 0;
        }
    }

    private static void createNonIncrementalFile(Path databaseFile) throws SQLException {
        try {
            Files.createDirectories(databaseFile.getParent());
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        try (Connection connection = SqlitePragmas.open(databaseFile); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE placeholder (x INTEGER)");
            statement.execute("DROP TABLE placeholder");
            assertEquals(0, SqlitePragmas.number(connection, "auto_vacuum"));
        }
    }
}

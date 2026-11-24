package gg.modl.backend.database.sql;

import gg.modl.backend.infrastructure.logging.OperatorNotice;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import javax.sql.DataSource;

final class SqliteDatabaseFile {
    public static final long HARD_HEAP_LIMIT_BYTES = 256L * 1024 * 1024;
    public static final int INCREMENTAL_AUTO_VACUUM = 2;
    static final String NOT_INCREMENTAL_NOTICE = "The database file does not return freed space to the disk. Restore it "
        + "from a nightly backup with `restore --input` to convert it.";

    private static final String WAL = "wal";

    private SqliteDatabaseFile() {
    }

    static void bootstrap(SqliteConnections connections, ConnectionTracker tracker, OperatorNotice notice) {
        Path file = connections.databaseFile();
        boolean existing = isExisting(file);
        DataSource bootstrap = tracker.track(connections.bootstrap(), ConnectionRole.MIGRATION);
        try (Connection connection = bootstrap.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA hard_heap_limit = " + HARD_HEAP_LIMIT_BYTES);
            if (existing) {
                reportNonIncrementalFile(statement, notice);
            } else {
                enableIncrementalAutoVacuum(statement, file);
            }
            requireWal(statement, file);
        } catch (SQLException e) {
            throw new IllegalStateException("The database " + file + " could not be opened: " + e.getMessage()
                + ". Check that the file is a modl database and the disk is not full; nothing was changed.", e);
        }
    }

    private static boolean isExisting(Path file) {
        try {
            return Files.exists(file) && Files.size(file) > 0;
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read " + file, e);
        }
    }

    private static void enableIncrementalAutoVacuum(Statement statement, Path file) throws SQLException {
        statement.execute("PRAGMA auto_vacuum = INCREMENTAL");
        int autoVacuum = pragmaInt(statement, "auto_vacuum");
        if (autoVacuum != INCREMENTAL_AUTO_VACUUM) {
            throw new IllegalStateException("The new database " + file + " did not accept incremental auto-vacuum (mode "
                + autoVacuum + ")");
        }
    }

    private static void reportNonIncrementalFile(Statement statement, OperatorNotice notice) throws SQLException {
        if (pragmaInt(statement, "auto_vacuum") != INCREMENTAL_AUTO_VACUUM) {
            notice.publish(NOT_INCREMENTAL_NOTICE);
        }
    }

    private static void requireWal(Statement statement, Path file) throws SQLException {
        try (ResultSet result = statement.executeQuery("PRAGMA journal_mode = WAL")) {
            String mode = result.next() ? result.getString(1) : "";
            if (!WAL.equalsIgnoreCase(mode)) {
                throw new IllegalStateException("The database " + file + " could not switch to WAL mode (it reports "
                    + mode + ")");
            }
        }
    }

    static int pragmaInt(Statement statement, String pragma) throws SQLException {
        try (ResultSet result = statement.executeQuery("PRAGMA " + pragma)) {
            return result.next() ? result.getInt(1) : -1;
        }
    }
}

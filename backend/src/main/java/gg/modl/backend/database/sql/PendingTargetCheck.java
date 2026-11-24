package gg.modl.backend.database.sql;

import gg.modl.backend.database.DataDirectoryProperties;
import gg.modl.backend.database.DatabaseDialect;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public final class PendingTargetCheck {
    static final String IN_USE = "another modl server is using it";
    public static final String NO_COPY = "it does not hold the copy modl made";
    static final String NO_FILE = "the database file is missing";

    public enum Outcome {
        READY,
        RETRY,
        REFUSED
    }

    public record Result(Outcome outcome, String reason) {
        public static final Result READY = new Result(Outcome.READY, "");

        public static Result retry(String reason) {
            return new Result(Outcome.RETRY, reason);
        }

        public static Result refused(String reason) {
            return new Result(Outcome.REFUSED, reason);
        }

        public boolean ready() {
            return outcome == Outcome.READY;
        }
    }

    private PendingTargetCheck() {
    }

    public static Result check(SqlDatabaseProperties target, DataDirectoryProperties dataDirectory, String moveToken) {
        return target.dialect().isExternal() ? externalCopy(target, moveToken) : builtInCopy(target, dataDirectory, moveToken);
    }

    private static Result externalCopy(SqlDatabaseProperties target, String moveToken) {
        DatabaseDialect dialect = target.dialect();
        try (Connection connection = ConnectionCheck.connect(target)) {
            if (!lockIsFree(connection, dialect)) {
                return Result.retry(IN_USE);
            }
            return holdsCopy(connection, dialect, moveToken);
        } catch (ConnectFailedException e) {
            return Result.retry(e.description());
        } catch (SQLException e) {
            log.debug("The new database could not be checked", e);
            return Result.retry(ConnectionCheck.describe(e));
        }
    }

    private static Result builtInCopy(SqlDatabaseProperties target, DataDirectoryProperties dataDirectory, String moveToken) {
        Path file = SqliteLocations.databaseFile(dataDirectory);
        if (!holdsContent(file)) {
            return Result.refused(NO_FILE);
        }
        SqliteNativeLibrary.initialize(SqliteLocations.nativeParent(target, dataDirectory));
        SqliteConnections connections = new SqliteConnections(file, SqliteLocations.temporaryDirectory(dataDirectory));
        try (Connection connection = connections.inspection().getConnection();
             Statement queryOnly = connection.createStatement()) {
            queryOnly.execute(SqliteConnections.QUERY_ONLY);
            return holdsCopy(connection, DatabaseDialect.SQLITE, moveToken);
        } catch (SQLException e) {
            log.debug("The built-in database could not be checked", e);
            return Result.refused(ConnectionCheck.describe(e));
        }
    }

    private static Result holdsCopy(Connection connection, DatabaseDialect dialect, String moveToken) throws SQLException {
        return DepartureMarks.read(connection, dialect).filter(departure -> departure.belongsTo(moveToken)).isPresent()
            ? Result.READY
            : Result.refused(NO_COPY);
    }

    private static boolean lockIsFree(Connection connection, DatabaseDialect dialect) throws SQLException {
        AdvisoryLockStatements statements = AdvisoryLockStatements.of(dialect, connection);
        if (!statements.tryLock(connection)) {
            return false;
        }
        statements.unlock(connection);
        return true;
    }

    private static boolean holdsContent(Path file) {
        try {
            return Files.isRegularFile(file) && Files.size(file) > 0;
        } catch (IOException e) {
            return false;
        }
    }
}

package gg.modl.backend.database.sql;

import gg.modl.backend.database.DatabaseDialect;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Optional;

public final class ConnectionCheck {
    private static final String SIGN_IN_STATE_CLASS = "28";
    private static final String SOCKET_TIMEOUT_PROPERTY = "socketTimeout";
    private static final Duration SOCKET_TIMEOUT = Duration.ofSeconds(15);

    private ConnectionCheck() {
    }

    public static Optional<String> failureToConnect(SqlDatabaseProperties database) {
        try {
            connect(database).close();
            return Optional.empty();
        } catch (ConnectFailedException e) {
            return Optional.of(e.description());
        } catch (SQLException e) {
            return Optional.of(describe(e));
        }
    }

    static Connection connect(SqlDatabaseProperties database) throws ConnectFailedException {
        DatabaseDialect dialect = database.dialect();
        DriverDataSource connections = ExternalSessions.migration(DriverDataSource.of(database), dialect)
            .withProperty(SOCKET_TIMEOUT_PROPERTY, String.valueOf(dialect.isMysqlFamily() ? SOCKET_TIMEOUT.toMillis()
                : SOCKET_TIMEOUT.toSeconds()));
        try {
            return connections.getConnection();
        } catch (SQLException e) {
            throw new ConnectFailedException(describe(e), isSignInRefusal(e), e);
        }
    }

    private static boolean isSignInRefusal(SQLException failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sql && sql.getSQLState() != null && sql.getSQLState().startsWith(SIGN_IN_STATE_CLASS)) {
                return true;
            }
        }
        return false;
    }

    static String describe(SQLException failure) {
        String message = failure.getMessage();
        return message == null || message.isBlank() ? failure.getClass().getSimpleName() : firstLine(message);
    }

    private static String firstLine(String message) {
        int end = message.indexOf('\n');
        return (end < 0 ? message : message.substring(0, end)).trim();
    }
}

package gg.modl.backend.database.sql;

public final class DatabaseInUseException extends DatabaseToolException {
    DatabaseInUseException(String message, Throwable cause) {
        super(message, cause);
    }
}

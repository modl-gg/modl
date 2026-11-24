package gg.modl.backend.database.sql;

import org.jspecify.annotations.Nullable;
import java.sql.SQLException;

public class DatabaseToolException extends RuntimeException {
    public DatabaseToolException(String message) {
        super(message);
    }

    public DatabaseToolException(String message, @Nullable Throwable cause) {
        super(message, cause);
    }

    static DatabaseToolException failedTo(String action, SQLException failure) {
        return new DatabaseToolException("Could not " + action + ": " + failure.getMessage(), failure);
    }
}

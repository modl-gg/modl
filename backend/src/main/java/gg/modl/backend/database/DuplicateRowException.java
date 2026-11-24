package gg.modl.backend.database;

import org.jspecify.annotations.Nullable;

public class DuplicateRowException extends RuntimeException {
    public DuplicateRowException(String message, @Nullable Throwable cause) {
        super(message, cause);
    }
}

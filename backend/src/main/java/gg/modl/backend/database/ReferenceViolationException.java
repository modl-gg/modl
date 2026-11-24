package gg.modl.backend.database;

public class ReferenceViolationException extends RuntimeException {
    public ReferenceViolationException(String message, Throwable cause) {
        super(message, cause);
    }
}

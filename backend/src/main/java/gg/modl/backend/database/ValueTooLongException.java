package gg.modl.backend.database;

public class ValueTooLongException extends RuntimeException {
    private final String message;

    public ValueTooLongException(int limit) {
        this("A value is longer than the " + limit + " characters allowed");
    }

    private ValueTooLongException(String message) {
        super(message);
        this.message = message;
    }

    public ValueTooLongException(String message, Throwable cause) {
        super(message, cause);
        this.message = message;
    }

    public String userMessage() {
        return message;
    }
}

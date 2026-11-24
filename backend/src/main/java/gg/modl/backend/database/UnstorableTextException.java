package gg.modl.backend.database;

public class UnstorableTextException extends RuntimeException {
    private static final String MESSAGE = "Text must not contain NUL characters";

    public UnstorableTextException() {
        super(MESSAGE);
    }

    public String userMessage() {
        return MESSAGE;
    }
}

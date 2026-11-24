package gg.modl.backend.database;

public class StaleClientVersionException extends RuntimeException {
    private final String message;
    private final long currentVersion;

    public StaleClientVersionException(String message, long currentVersion) {
        super(message);
        this.message = message;
        this.currentVersion = currentVersion;
    }

    public String userMessage() {
        return message;
    }

    public long currentVersion() {
        return currentVersion;
    }
}

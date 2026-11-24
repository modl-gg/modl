package gg.modl.backend.database;

import java.time.Duration;

public class DatabaseBusyException extends RuntimeException {
    public static final Duration POOL_RETRY_AFTER = Duration.ofSeconds(1);
    public static final Duration LANE_RETRY_AFTER = Duration.ofSeconds(2);

    private final Duration retryAfter;

    public DatabaseBusyException(String message, Duration retryAfter, Throwable cause) {
        super(message, cause);
        this.retryAfter = retryAfter;
    }

    public DatabaseBusyException(String message, Duration retryAfter) {
        super(message);
        this.retryAfter = retryAfter;
    }

    public Duration retryAfter() {
        return retryAfter;
    }
}

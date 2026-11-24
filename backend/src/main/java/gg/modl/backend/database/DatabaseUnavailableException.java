package gg.modl.backend.database;

import java.time.Duration;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

public class DatabaseUnavailableException extends RuntimeException {
    public enum Reason {
        SHUTTING_DOWN("DATABASE_UNAVAILABLE", "The modl server is shutting down", null),
        LOCK_LOST("DATABASE_UNAVAILABLE", "The modl server lost its database lock and stopped serving", null),
        DISK_FULL("DATABASE_DISK_FULL", "The database cannot store more data because its disk is full", null),
        MOVING("DATABASE_MOVING", "modl is moving to another database; changes are paused until it restarts",
            Duration.ofSeconds(30));

        private final String code;
        private final String message;
        private final @Nullable Duration retryAfter;

        Reason(String code, String message, @Nullable Duration retryAfter) {
            this.code = code;
            this.message = message;
            this.retryAfter = retryAfter;
        }

        public String code() {
            return code;
        }

        public Optional<Duration> retryAfter() {
            return Optional.ofNullable(retryAfter);
        }
    }

    private final Reason reason;

    public DatabaseUnavailableException(Reason reason) {
        super(reason.message);
        this.reason = reason;
    }

    public DatabaseUnavailableException(Reason reason, Throwable cause) {
        super(reason.message, cause);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }

    public String code() {
        return reason.code();
    }

    public String userMessage() {
        return reason.message;
    }
}

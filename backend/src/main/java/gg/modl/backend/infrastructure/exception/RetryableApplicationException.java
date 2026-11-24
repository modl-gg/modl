package gg.modl.backend.infrastructure.exception;

import java.time.Duration;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;

public abstract class RetryableApplicationException extends BaseApplicationException {
    @Nullable
    private final Duration retryAfter;

    protected RetryableApplicationException(String message, HttpStatus status) {
        super(message, status);
        this.retryAfter = null;
    }

    protected RetryableApplicationException(String message, HttpStatus status, Throwable cause) {
        super(message, status, cause);
        this.retryAfter = null;
    }

    protected RetryableApplicationException(String message, HttpStatus status, String machineCode, Duration retryAfter) {
        super(message, status, machineCode);
        this.retryAfter = retryAfter;
    }

    public Optional<Duration> retryAfter() {
        return Optional.ofNullable(retryAfter);
    }
}

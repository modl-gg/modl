package gg.modl.backend.infrastructure.exception;

import java.time.Duration;
import org.springframework.http.HttpStatus;

public class TooManyRequestsException extends RetryableApplicationException {
    public TooManyRequestsException(String message) {
        super(message, HttpStatus.TOO_MANY_REQUESTS);
    }

    public TooManyRequestsException(String message, String machineCode, Duration retryAfter) {
        super(message, HttpStatus.TOO_MANY_REQUESTS, machineCode, retryAfter);
    }
}

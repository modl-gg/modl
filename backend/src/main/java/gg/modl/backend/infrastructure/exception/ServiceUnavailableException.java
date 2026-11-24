package gg.modl.backend.infrastructure.exception;

import java.time.Duration;
import org.springframework.http.HttpStatus;

public class ServiceUnavailableException extends RetryableApplicationException {
    public ServiceUnavailableException(String message) {
        super(message, HttpStatus.SERVICE_UNAVAILABLE);
    }

    public ServiceUnavailableException(String message, Throwable cause) {
        super(message, HttpStatus.SERVICE_UNAVAILABLE, cause);
    }

    public ServiceUnavailableException(String message, String machineCode, Duration retryAfter) {
        super(message, HttpStatus.SERVICE_UNAVAILABLE, machineCode, retryAfter);
    }
}

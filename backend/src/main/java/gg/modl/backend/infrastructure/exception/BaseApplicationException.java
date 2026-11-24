package gg.modl.backend.infrastructure.exception;

import lombok.AccessLevel;
import lombok.Getter;
import org.springframework.http.HttpStatus;

@Getter
public abstract class BaseApplicationException extends RuntimeException {
    @Getter(AccessLevel.NONE)
    private final String message;
    private final HttpStatus status;
    private final String machineCode;

    protected BaseApplicationException(String message, HttpStatus status) {
        this(message, status, HttpErrorMapping.machineCode(status));
    }

    protected BaseApplicationException(String message, HttpStatus status, String machineCode) {
        super(message);
        this.message = message;
        this.status = status;
        this.machineCode = machineCode;
    }

    protected BaseApplicationException(String message, HttpStatus status, Throwable cause) {
        super(message, cause);
        this.message = message;
        this.status = status;
        this.machineCode = HttpErrorMapping.machineCode(status);
    }

    public String userMessage() {
        return message;
    }
}

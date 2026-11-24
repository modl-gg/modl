package gg.modl.backend.storage;

import gg.modl.backend.infrastructure.exception.BaseApplicationException;
import org.springframework.http.HttpStatus;

public class StorageLimitExceededException extends BaseApplicationException {

    private final String reason;

    public StorageLimitExceededException(String message) {
        super(message, HttpStatus.INSUFFICIENT_STORAGE);
        this.reason = message;
    }

    @Override
    public String getMessage() {
        return reason;
    }
}

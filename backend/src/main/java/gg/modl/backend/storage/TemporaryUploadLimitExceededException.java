package gg.modl.backend.storage;

import gg.modl.backend.infrastructure.exception.BaseApplicationException;
import org.springframework.http.HttpStatus;

public class TemporaryUploadLimitExceededException extends BaseApplicationException {

    private static final String MESSAGE =
        "Temporary upload limit reached. Attach your files to a ticket or appeal, or try again later.";

    public TemporaryUploadLimitExceededException() {
        super(MESSAGE, HttpStatus.BAD_REQUEST);
    }

    @Override
    public String getMessage() {
        return MESSAGE;
    }
}

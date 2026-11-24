package gg.modl.backend.storage;

import gg.modl.backend.infrastructure.exception.BaseApplicationException;
import org.springframework.http.HttpStatus;

public class UploadRecordFailedException extends BaseApplicationException {

    public UploadRecordFailedException() {
        super("Failed to record upload", HttpStatus.INTERNAL_SERVER_ERROR);
    }
}

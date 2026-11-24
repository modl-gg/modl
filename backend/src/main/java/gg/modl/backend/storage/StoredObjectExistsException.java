package gg.modl.backend.storage;

import gg.modl.backend.infrastructure.exception.BaseApplicationException;
import org.springframework.http.HttpStatus;

public class StoredObjectExistsException extends BaseApplicationException {

    public StoredObjectExistsException() {
        super("An object with this key already exists", HttpStatus.CONFLICT);
    }
}

package gg.modl.backend.database.backup;

import java.io.IOException;

final class InvalidArchiveException extends IOException {
    InvalidArchiveException(String message) {
        super(message);
    }

    InvalidArchiveException(String message, Throwable cause) {
        super(message, cause);
    }

    static InvalidArchiveException damaged(String detail) {
        return new InvalidArchiveException("Backup is damaged: " + detail);
    }

    static InvalidArchiveException unreadable(String entry, Throwable cause) {
        return new InvalidArchiveException("Backup is damaged: " + entry + " cannot be read", cause);
    }
}

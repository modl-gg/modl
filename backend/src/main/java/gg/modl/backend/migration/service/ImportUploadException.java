package gg.modl.backend.migration.service;

import gg.modl.backend.infrastructure.exception.BaseApplicationException;
import org.springframework.http.HttpStatus;

public final class ImportUploadException extends BaseApplicationException {
    public static final String NOT_ACCEPTED = "IMPORT_FILE_NOT_ACCEPTED";
    public static final String LENGTH_REQUIRED = "IMPORT_LENGTH_REQUIRED";
    public static final String TOO_LARGE = "IMPORT_FILE_TOO_LARGE";
    public static final String DISK_FULL = "IMPORT_DISK_FULL";
    public static final String INCOMPLETE = "IMPORT_UPLOAD_INCOMPLETE";

    private static final long BYTES_PER_MEBIBYTE = 1024L * 1024;

    private ImportUploadException(String message, HttpStatus status, String code) {
        super(message, status, code);
    }

    static ImportUploadException notAccepted() {
        return new ImportUploadException("This import does not accept a file", HttpStatus.CONFLICT, NOT_ACCEPTED);
    }

    static ImportUploadException lengthRequired() {
        return new ImportUploadException("The upload must declare its size", HttpStatus.LENGTH_REQUIRED, LENGTH_REQUIRED);
    }

    static ImportUploadException tooLarge(long maxBytes) {
        return new ImportUploadException("The import file exceeds the limit of " + maxBytes / BYTES_PER_MEBIBYTE + " MB",
            HttpStatus.CONTENT_TOO_LARGE, TOO_LARGE);
    }

    static ImportUploadException diskFull() {
        return new ImportUploadException("There is not enough free disk space for the import file",
            HttpStatus.SERVICE_UNAVAILABLE, DISK_FULL);
    }

    static ImportUploadException incomplete() {
        return new ImportUploadException("The upload does not match its declared size or took too long",
            HttpStatus.BAD_REQUEST, INCOMPLETE);
    }
}

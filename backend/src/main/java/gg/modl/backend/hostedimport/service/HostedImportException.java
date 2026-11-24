package gg.modl.backend.hostedimport.service;

import gg.modl.backend.infrastructure.exception.BaseApplicationException;
import org.springframework.http.HttpStatus;

public class HostedImportException extends BaseApplicationException {
    public HostedImportException(HttpStatus status, String code, String message) {
        super(message, status, code);
    }

    static HostedImportException notFound() {
        return new HostedImportException(HttpStatus.NOT_FOUND, "IMPORT_NOT_FOUND", "No import has this id");
    }

    static HostedImportException wrongState() {
        return new HostedImportException(HttpStatus.CONFLICT, "IMPORT_WRONG_STATE", "The import is not in a state that allows this");
    }

    static HostedImportException notAvailable() {
        return new HostedImportException(HttpStatus.CONFLICT, "IMPORT_NOT_AVAILABLE", "An import is not possible on this installation");
    }

    static HostedImportException inProgress() {
        return new HostedImportException(HttpStatus.CONFLICT, "IMPORT_IN_PROGRESS", "An import is already in progress");
    }

    static HostedImportException diskSpace() {
        return new HostedImportException(HttpStatus.INSUFFICIENT_STORAGE, "IMPORT_DISK_SPACE", "There is not enough free disk space");
    }
}

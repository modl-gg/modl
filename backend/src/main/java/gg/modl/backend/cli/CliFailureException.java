package gg.modl.backend.cli;

final class CliFailureException extends RuntimeException {
    CliFailureException(String message) {
        super(message);
    }

    CliFailureException(String message, Throwable cause) {
        super(message, cause);
    }
}

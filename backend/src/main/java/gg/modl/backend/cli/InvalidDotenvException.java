package gg.modl.backend.cli;

public final class InvalidDotenvException extends RuntimeException {

    InvalidDotenvException(String message, Throwable cause) {
        super(message, cause);
    }
}

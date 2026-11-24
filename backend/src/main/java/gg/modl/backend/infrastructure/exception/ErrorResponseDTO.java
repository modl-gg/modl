package gg.modl.backend.infrastructure.exception;

import org.jspecify.annotations.Nullable;

public record ErrorResponseDTO(int status, String error, String message, @Nullable String code, @Nullable Long retryAfterSeconds) {

    public static ErrorResponseDTO of(int status, String message, String code) {
        return new ErrorResponseDTO(status, message, message, code, null);
    }

    public ErrorResponseDTO retryingAfter(long seconds) {
        return new ErrorResponseDTO(status, error, message, code, seconds);
    }
}

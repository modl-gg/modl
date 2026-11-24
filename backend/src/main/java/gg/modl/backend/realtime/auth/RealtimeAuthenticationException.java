package gg.modl.backend.realtime.auth;

import gg.modl.proto.modl.v1.ErrorCode;
import org.jspecify.annotations.Nullable;

import java.util.Optional;

public class RealtimeAuthenticationException extends Exception {
    private final ErrorCode errorCode;
    private final String message;
    private final @Nullable String serverVersion;

    public RealtimeAuthenticationException(ErrorCode errorCode, String message) {
        this(errorCode, message, null);
    }

    private RealtimeAuthenticationException(ErrorCode errorCode, String message, @Nullable String serverVersion) {
        super(message);
        this.errorCode = errorCode;
        this.message = message;
        this.serverVersion = serverVersion;
    }

    public String userMessage() {
        return message;
    }

    public static RealtimeAuthenticationException versionMismatch(String message, String serverVersion) {
        return new RealtimeAuthenticationException(ErrorCode.ERROR_CODE_VERSION_MISMATCH, message, serverVersion);
    }

    public ErrorCode getErrorCode() {
        return errorCode;
    }

    public Optional<String> serverVersion() {
        return Optional.ofNullable(serverVersion);
    }
}

package gg.modl.minecraft.api.http;

import lombok.Getter;
import org.jspecify.annotations.Nullable;

@Getter
public class ApiClientException extends RuntimeException {
    private static final int PERMISSION_DENIED = 403;
    private static final int TOO_MANY_REQUESTS = 429;

    private final int statusCode;
    private final @Nullable String code;

    public ApiClientException(int statusCode, String message) {
        this(statusCode, message, null);
    }

    public ApiClientException(int statusCode, String message, @Nullable String code) {
        super(message);
        this.statusCode = statusCode;
        this.code = code != null && !code.isEmpty() ? code : null;
    }

    public boolean isPermissionDenied() {
        return statusCode == PERMISSION_DENIED;
    }

    public boolean isRateLimited() {
        return statusCode == TOO_MANY_REQUESTS;
    }

    public static boolean isPermissionDenied(Throwable failure) {
        Throwable cause = failure.getCause() != null ? failure.getCause() : failure;
        return cause instanceof ApiClientException && ((ApiClientException) cause).isPermissionDenied();
    }

    public static @Nullable String codeOf(Throwable failure) {
        Throwable cause = failure.getCause() != null ? failure.getCause() : failure;
        return cause instanceof ApiClientException ? ((ApiClientException) cause).getCode() : null;
    }
}

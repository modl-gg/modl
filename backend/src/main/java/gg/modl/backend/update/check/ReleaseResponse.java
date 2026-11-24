package gg.modl.backend.update.check;

import org.jspecify.annotations.Nullable;
import java.time.Duration;

final class ReleaseResponse {
    static final int OK = 200;
    static final int NOT_MODIFIED = 304;
    static final int NOT_FOUND = 404;
    static final int FORBIDDEN = 403;
    static final int TOO_MANY_REQUESTS = 429;

    private final int status;
    private final byte[] body;
    private final @Nullable String etag;
    private final @Nullable Duration retryAfter;

    ReleaseResponse(int status, byte[] body, @Nullable String etag, @Nullable Duration retryAfter) {
        this.status = status;
        this.body = body.clone();
        this.etag = etag;
        this.retryAfter = retryAfter;
    }

    int status() {
        return status;
    }

    byte[] body() {
        return body.clone();
    }

    @Nullable String etag() {
        return etag;
    }

    @Nullable Duration retryAfter() {
        return retryAfter;
    }

    boolean isRateLimited() {
        return status == TOO_MANY_REQUESTS || (status == FORBIDDEN && retryAfter != null);
    }
}

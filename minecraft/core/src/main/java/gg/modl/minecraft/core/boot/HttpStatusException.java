package gg.modl.minecraft.core.boot;

import java.io.IOException;

final class HttpStatusException extends IOException {
    private static final int UNAUTHORIZED = 401;
    private static final int FORBIDDEN = 403;
    private static final int MAX_BODY_IN_MESSAGE = 200;

    private final int statusCode;

    private HttpStatusException(int statusCode, String message) {
        super(message);
        this.statusCode = statusCode;
    }

    static HttpStatusException withBody(int statusCode, String body) {
        return new HttpStatusException(statusCode, "Server returned HTTP " + statusCode
                + (body.isEmpty() ? " with no response body" : ": " + truncate(body)));
    }

    static HttpStatusException redirect(int statusCode, String location) {
        String target = location == null || location.isEmpty() ? "another address" : location;
        return new HttpStatusException(statusCode, "Server redirected (HTTP " + statusCode + ") to " + target
                + "; set the modl server address to the final URL (for example https instead of http)");
    }

    int statusCode() {
        return statusCode;
    }

    private static String truncate(String body) {
        return body.length() > MAX_BODY_IN_MESSAGE ? body.substring(0, MAX_BODY_IN_MESSAGE) + "..." : body;
    }

    boolean isCredentialRejection() {
        return statusCode == UNAUTHORIZED || statusCode == FORBIDDEN;
    }
}

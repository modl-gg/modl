package gg.modl.backend.ai.http;

import org.jspecify.annotations.Nullable;
import java.net.URI;
import java.util.Map;

public record AiHttpRequest(String method, URI uri, Map<String, String> headers, @Nullable String jsonBody, int responseLimit) {
    public static final int COMPLETION_LIMIT = 1024 * 1024;
    public static final int MODEL_PAGE_LIMIT = 4 * 1024 * 1024;

    public AiHttpRequest {
        headers = Map.copyOf(headers);
    }

    public static AiHttpRequest get(URI uri, Map<String, String> headers, int responseLimit) {
        return new AiHttpRequest("GET", uri, headers, null, responseLimit);
    }

    public static AiHttpRequest postJson(URI uri, Map<String, String> headers, String body, int responseLimit) {
        return new AiHttpRequest("POST", uri, headers, body, responseLimit);
    }

    @Override
    public String toString() {
        return "AiHttpRequest[" + method + " " + uri + "]";
    }
}

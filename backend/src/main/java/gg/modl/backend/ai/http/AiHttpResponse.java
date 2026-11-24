package gg.modl.backend.ai.http;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;

public record AiHttpResponse(int status, Map<String, String> headers, String body) {
    public AiHttpResponse {
        headers = Map.copyOf(headers);
    }

    public boolean successful() {
        return status >= 200 && status < 300;
    }

    public boolean redirect() {
        return status >= 300 && status < 400;
    }

    public Optional<String> header(String name) {
        return Optional.ofNullable(headers.get(name.toLowerCase(Locale.ROOT)));
    }

    @Override
    public String toString() {
        return "AiHttpResponse[status=" + status + ", bytes=" + body.length() + "]";
    }
}

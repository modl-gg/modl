package gg.modl.backend.storage;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

public record UploadTarget(String url, String method, Map<String, String> headers, Instant expiresAt) {
    public static final Duration VALIDITY = Duration.ofMinutes(15);

    public UploadTarget {
        headers = Map.copyOf(headers);
    }
}

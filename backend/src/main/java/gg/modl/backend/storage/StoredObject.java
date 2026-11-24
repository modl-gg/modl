package gg.modl.backend.storage;

import java.time.Instant;

public record StoredObject(String key, long size, String contentType, Instant lastModified) {
    public static final String DEFAULT_CONTENT_TYPE = "application/octet-stream";

    public StoredObject {
        contentType = contentType == null || contentType.isBlank() ? DEFAULT_CONTENT_TYPE : contentType;
    }
}

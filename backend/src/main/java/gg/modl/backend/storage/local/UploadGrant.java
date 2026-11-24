package gg.modl.backend.storage.local;

import java.time.Instant;

public record UploadGrant(String key, long expiresAtEpochSecond, long contentLength, String contentType) {

    String canonicalForm() {
        return String.join("\n", key, Long.toString(expiresAtEpochSecond), Long.toString(contentLength), contentType);
    }

    boolean isExpired(Instant now) {
        return now.getEpochSecond() > expiresAtEpochSecond;
    }

    public boolean exceededBy(long length) {
        return length > contentLength;
    }

    public boolean matches(long length) {
        return length == contentLength;
    }
}

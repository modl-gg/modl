package gg.modl.backend.storage.repository;

import org.jspecify.annotations.Nullable;
import java.time.Instant;

public record StorageFile(
    String key,
    @Nullable String fileName,
    long size,
    @Nullable String contentType,
    @Nullable String category,
    Instant createdAt
) {
}

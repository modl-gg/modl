package gg.modl.backend.storage;

import java.time.Instant;

public record ListedObject(String key, long size, Instant lastModified) {
}

package gg.modl.backend.settings.service;

import org.jspecify.annotations.Nullable;
import java.time.Instant;

public record VersionedSettings<T>(T data, long version, @Nullable Instant updatedAt) {
}

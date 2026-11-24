package gg.modl.backend.hostedimport.model;

import org.jspecify.annotations.Nullable;
import java.time.Instant;
import java.util.List;

public record ArchiveSummary(
    String exportId,
    String sourceVersion,
    @Nullable Instant exportedAt,
    @Nullable String serverName,
    @Nullable String subdomain,
    List<NamedCount> excludedCollections,
    List<NamedCount> skippedObjects
) {
    public ArchiveSummary {
        excludedCollections = List.copyOf(excludedCollections);
        skippedObjects = List.copyOf(skippedObjects);
    }

    public record NamedCount(String name, String reason, long count) {
    }
}

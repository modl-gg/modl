package gg.modl.backend.knowledgebase.repository;

import java.time.Instant;
import lombok.Builder;
import org.jspecify.annotations.Nullable;

@Builder
public record KnowledgebaseCategoryPatch(
    @Nullable String name,
    @Nullable String slug,
    @Nullable String description,
    @Nullable Boolean visible,
    Instant updatedAt
) {
}

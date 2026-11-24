package gg.modl.backend.knowledgebase.repository;

import java.time.Instant;
import lombok.Builder;
import org.jspecify.annotations.Nullable;

@Builder
public record KnowledgebaseArticlePatch(
    @Nullable String title,
    @Nullable String slug,
    @Nullable String content,
    @Nullable Boolean visible,
    Instant updatedAt
) {
    public boolean changesText() {
        return title != null || slug != null || content != null;
    }
}

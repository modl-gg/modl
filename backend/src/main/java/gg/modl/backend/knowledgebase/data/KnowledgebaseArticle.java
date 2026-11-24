package gg.modl.backend.knowledgebase.data;

import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KnowledgebaseArticle {
    private String id;

    private String title;
    private String slug;
    private @Nullable String content;
    private String categoryId;
    private int ordinal;
    private boolean isVisible;
    private boolean starter;
    private Instant createdAt;
    private Instant updatedAt;
}

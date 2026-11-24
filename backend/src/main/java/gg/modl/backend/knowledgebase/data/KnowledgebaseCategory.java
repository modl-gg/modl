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
public class KnowledgebaseCategory {
    private String id;

    private String name;
    private String slug;
    private @Nullable String description;
    private int ordinal;
    private boolean isVisible;

    private Instant createdAt;
    private Instant updatedAt;
}

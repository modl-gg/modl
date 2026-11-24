package gg.modl.backend.ai.catalog;

import gg.modl.backend.ai.model.AiFailureKind;
import gg.modl.backend.ai.model.AiModelInfo;
import org.jspecify.annotations.Nullable;
import java.time.Instant;
import java.util.List;

public record AiModelCatalog(List<AiModelInfo> models, boolean stale, @Nullable Instant fetchedAt, @Nullable AiFailureKind failure,
                             boolean complete) {
    public AiModelCatalog {
        models = List.copyOf(models);
    }
}

package gg.modl.backend.ai.model;

import org.jspecify.annotations.Nullable;
import java.util.List;

public record AiModelListing(List<AiModelInfo> models, @Nullable AiFailureKind failure, boolean complete) {
    public AiModelListing {
        models = List.copyOf(models);
    }

    public static AiModelListing failed(AiFailureKind failure) {
        return new AiModelListing(List.of(), failure, false);
    }

    public boolean succeeded() {
        return failure == null;
    }
}

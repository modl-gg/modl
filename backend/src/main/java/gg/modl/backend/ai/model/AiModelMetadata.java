package gg.modl.backend.ai.model;

import java.util.Optional;

public interface AiModelMetadata {
    Optional<AiModelInfo> cached(AiConnection connection);
}

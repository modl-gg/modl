package gg.modl.backend.ai.model;

import org.jspecify.annotations.Nullable;
import java.time.Instant;

public record AiModelInfo(
    String id,
    @Nullable String displayName,
    boolean chatCapable,
    boolean deprecated,
    @Nullable Instant retiresAt,
    @Nullable Boolean structuredOutputs,
    @Nullable Boolean effortHint
) {
    public AiModelInfo withChatCapable(boolean capable) {
        return new AiModelInfo(id, displayName, capable, deprecated, retiresAt, structuredOutputs, effortHint);
    }
}

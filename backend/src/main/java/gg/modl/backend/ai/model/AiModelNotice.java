package gg.modl.backend.ai.model;

import org.jspecify.annotations.Nullable;
import java.time.Instant;

public record AiModelNotice(Warning warning, @Nullable Instant retiresAt) {
    public enum Warning {
        DEPRECATED,
        RETIRING,
        NOT_LISTED
    }
}

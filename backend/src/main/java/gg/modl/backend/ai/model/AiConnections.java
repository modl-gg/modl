package gg.modl.backend.ai.model;

import org.jspecify.annotations.Nullable;
import java.time.Instant;

public interface AiConnections {
    AiConnectionState state();

    void recordShape(AiConnection used, AiRequestShape shape, @Nullable Instant verifiedAt);
}

package gg.modl.backend.ai.probe;

import gg.modl.backend.ai.model.AiFailureKind;
import gg.modl.backend.ai.model.AiRequestShape.OutputMode;
import org.jspecify.annotations.Nullable;
import java.time.Duration;

public record AiProbeResult(
    boolean success,
    @Nullable AiFailureKind failure,
    @Nullable OutputMode outputMode,
    Duration latency,
    @Nullable Integer upstreamStatus,
    boolean recordedForEffectiveConnection
) {
}

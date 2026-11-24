package gg.modl.backend.iplookup.probe;

import org.jspecify.annotations.Nullable;
import java.time.Duration;

public record IpLookupProbeResult(
    IpLookupProbeOutcome outcome,
    String testAddress,
    Duration latency,
    @Nullable Integer upstreamStatus,
    boolean keyChecked
) {
}

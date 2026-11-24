package gg.modl.backend.ai.connection;

import gg.modl.backend.ai.model.AiConnectionState.Managed;
import gg.modl.backend.ai.model.AiConnectionState.Readiness;
import gg.modl.backend.ai.model.AiProviderDefinition;
import gg.modl.backend.ai.model.AiRequestShape.OutputMode;
import gg.modl.backend.ai.model.AiUsageLimits;
import org.jspecify.annotations.Nullable;
import java.time.Instant;
import java.util.List;

public record AiSettingsView(
    List<AiProviderDefinition> providers,
    @Nullable String activeProvider,
    List<ConnectionInfo> connections,
    Managed managed,
    Readiness readiness,
    AiUsageLimits limits,
    long limitsVersion
) {
    public record ConnectionInfo(
        String provider,
        String baseUrl,
        boolean hasApiKey,
        @Nullable String model,
        @Nullable OutputMode outputMode,
        @Nullable Instant verifiedAt
    ) {
    }
}

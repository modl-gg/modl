package gg.modl.backend.ai.model;

import org.jspecify.annotations.Nullable;
import java.util.Optional;

public record AiConnectionState(
    Readiness readiness,
    @Nullable AiProviderDefinition provider,
    Optional<AiConnection> connection,
    Managed managed,
    AiUsageLimits limits
) {
    public Optional<AiConnection> ready() {
        return readiness == Readiness.READY ? connection : Optional.empty();
    }

    public enum Readiness {
        READY,
        NOT_CONFIGURED,
        KEY_MISSING,
        MODEL_MISSING,
        BASE_URL_INVALID,
        CONFIG_PROVIDER_MISSING,
        CONFIG_PROVIDER_UNKNOWN
    }

    public record Managed(boolean provider, boolean apiKey, boolean model, boolean baseUrl) {
        public static final Managed NONE = new Managed(false, false, false, false);

        public boolean any() {
            return provider || apiKey || model || baseUrl;
        }
    }
}

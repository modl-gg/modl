package gg.modl.backend.ai.protocol;

import gg.modl.backend.ai.http.AiAddressPolicy;
import gg.modl.backend.ai.http.AiHttpTransport;
import gg.modl.backend.ai.http.LocalAiServer;
import gg.modl.backend.ai.model.AiCompletionRequest;
import gg.modl.backend.ai.model.AiConnection;
import gg.modl.backend.ai.model.AiDeadline;
import gg.modl.backend.ai.provider.AiProviderCatalog;
import gg.modl.backend.ai.service.ChatModerationSchema;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;

final class ProtocolFixtures {
    static final String KEY = "sk-test-0123456789";
    static final AiCompletionRequest REQUEST = new AiCompletionRequest("system text JSON", "user text",
        ChatModerationSchema.forTypes(List.of("6")), 4096);
    static final AiProviderCatalog CATALOG = new AiProviderCatalog();

    private ProtocolFixtures() {
    }

    static AiProviderCall call() {
        return new AiProviderCall(new AiHttpTransport(new AiAddressPolicy()));
    }

    static AiConnection connection(String provider, LocalAiServer server, @Nullable String key, String model) {
        return new AiConnection(CATALOG.find(provider).orElseThrow(), URI.create(server.baseUrl() + "/v1"), key, model, null);
    }

    static AiDeadline deadline() {
        return AiDeadline.after(Duration.ofSeconds(20));
    }

    static JsonNode json(String body) {
        return AiJson.parse(body).orElseThrow();
    }
}

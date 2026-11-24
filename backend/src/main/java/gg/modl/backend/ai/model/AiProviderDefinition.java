package gg.modl.backend.ai.model;

import org.jspecify.annotations.Nullable;
import java.net.URI;
import java.util.List;
import java.util.Optional;

public record AiProviderDefinition(
    String id,
    String displayName,
    Group group,
    AiProtocol protocol,
    List<Endpoint> endpoints,
    boolean editableBaseUrl,
    KeyPolicy keyPolicy,
    @Nullable URI keyPage,
    AiRequestShape initialShape,
    List<AiRequestShape.OutputMode> ladder,
    OpenAiChatDialect dialect
) {
    public AiProviderDefinition {
        endpoints = List.copyOf(endpoints);
        ladder = List.copyOf(ladder);
        if (ladder.isEmpty() || ladder.getFirst() != initialShape.outputMode()) {
            throw new IllegalArgumentException("The ladder of " + id + " must start at its initial output mode");
        }
    }

    public boolean requiresKey() {
        return keyPolicy == KeyPolicy.REQUIRED;
    }

    public Optional<URI> defaultBaseUrl() {
        return endpoints.stream().findFirst().map(Endpoint::baseUrl);
    }

    public boolean offers(URI baseUrl) {
        return endpoints.stream().anyMatch(endpoint -> endpoint.baseUrl().equals(baseUrl));
    }

    public Optional<AiRequestShape.OutputMode> rungBelow(AiRequestShape.OutputMode mode) {
        int index = ladder.indexOf(mode);
        return index < 0 || index + 1 >= ladder.size() ? Optional.empty() : Optional.of(ladder.get(index + 1));
    }

    public enum Group {
        CLOUD,
        GATEWAY,
        LOCAL,
        CUSTOM
    }

    public enum Region {
        GLOBAL,
        CHINA,
        SINGAPORE,
        US
    }

    public enum KeyPolicy {
        REQUIRED,
        OPTIONAL
    }

    public record Endpoint(Region region, URI baseUrl) {
    }
}

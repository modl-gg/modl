package gg.modl.backend.ai.connection;

import gg.modl.backend.ai.connection.AiProviderSettings.StoredConnection;
import gg.modl.backend.ai.model.AiProviderDefinition;
import org.jspecify.annotations.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

record AiSettingsChange(AiProviderSettings before, AiProviderSettings after) {
    Optional<String> describeConnection(AiProviderDefinition provider) {
        StoredConnection previous = before.connection(provider.id()).orElse(StoredConnection.EMPTY);
        StoredConnection next = after.connection(provider.id()).orElse(StoredConnection.EMPTY);
        List<String> parts = new ArrayList<>();
        if (!Objects.equals(previous.baseUrl(), next.baseUrl())) {
            parts.add(next.baseUrl() == null ? "base URL removed" : "base URL set to " + next.baseUrl());
        }
        if (!Objects.equals(previous.apiKey(), next.apiKey())) {
            parts.add(keyChange(previous.apiKey(), next.apiKey()));
        }
        if (!Objects.equals(previous.model(), next.model())) {
            parts.add(next.model() == null ? "model removed" : "model set to " + next.model());
        }
        if (activated(provider)) {
            parts.add("made the active provider");
        }
        return parts.isEmpty()
            ? Optional.empty()
            : Optional.of("Changed the " + provider.displayName() + " AI connection: " + String.join(", ", parts));
    }

    Optional<String> describeSettings(@Nullable AiProviderDefinition activated) {
        List<String> parts = new ArrayList<>();
        if (!Objects.equals(before.activeProvider(), after.activeProvider())) {
            parts.add(activated == null ? "turned the AI provider off" : "made " + activated.displayName() + " the active provider");
        }
        if (!before.effectiveLimits().equals(after.effectiveLimits())) {
            parts.add("changed the usage limits");
        }
        return parts.isEmpty() ? Optional.empty() : Optional.of("Changed the AI settings: " + String.join(", ", parts));
    }

    boolean removed(String providerId) {
        return before.connection(providerId).isPresent() && after.connection(providerId).isEmpty();
    }

    private boolean activated(AiProviderDefinition provider) {
        return provider.id().equals(after.activeProvider()) && !provider.id().equals(before.activeProvider());
    }

    private static String keyChange(@Nullable String previous, @Nullable String next) {
        if (next == null) {
            return "API key removed";
        }
        return previous == null ? "API key added" : "API key replaced";
    }
}

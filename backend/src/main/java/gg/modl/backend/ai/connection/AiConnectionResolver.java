package gg.modl.backend.ai.connection;

import gg.modl.backend.ai.connection.AiProviderSettings.StoredConnection;
import gg.modl.backend.ai.http.AiBaseUrlPolicy;
import gg.modl.backend.ai.model.AiConnection;
import gg.modl.backend.ai.model.AiConnectionState;
import gg.modl.backend.ai.model.AiConnectionState.Managed;
import gg.modl.backend.ai.model.AiConnectionState.Readiness;
import gg.modl.backend.ai.model.AiProviderDefinition;
import gg.modl.backend.ai.model.AiRequestShape;
import gg.modl.backend.ai.model.AiUsageLimits;
import gg.modl.backend.ai.provider.AiProviderCatalog;
import java.net.URI;
import java.util.Objects;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

public final class AiConnectionResolver {
    private final AiEnvironmentProperties environment;
    private final AiProviderCatalog catalog;
    private final AiBaseUrlPolicy baseUrlPolicy;

    public AiConnectionResolver(AiEnvironmentProperties environment, AiProviderCatalog catalog, AiBaseUrlPolicy baseUrlPolicy) {
        this.environment = environment;
        this.catalog = catalog;
        this.baseUrlPolicy = baseUrlPolicy;
    }

    public Managed managed() {
        boolean providerSet = environmentProvider() != null;
        return new Managed(providerSet, environmentKey() != null, environment.model() != null,
            environment.baseUrl() != null || environmentKey() != null);
    }

    @Nullable
    public String environmentProvider() {
        if (environment.provider() != null) {
            return environment.provider();
        }
        return usesGeminiAlias() ? AiProviderCatalog.GEMINI : null;
    }

    public AiConnectionState resolve(AiProviderSettings stored) {
        AiUsageLimits limits = stored.effectiveLimits();
        String environmentProvider = environmentProvider();
        if (environmentProvider == null) {
            if (environmentKey() != null || environment.model() != null || environment.baseUrl() != null) {
                return failed(Readiness.CONFIG_PROVIDER_MISSING, limits);
            }
            return catalog.find(stored.activeProvider())
                .map(provider -> effective(provider, stored, limits))
                .orElseGet(() -> failed(Readiness.NOT_CONFIGURED, limits));
        }
        return catalog.find(environmentProvider)
            .map(provider -> effective(provider, stored, limits))
            .orElseGet(() -> failed(Readiness.CONFIG_PROVIDER_UNKNOWN, limits));
    }

    public AiConnectionState effective(AiProviderDefinition provider, AiProviderSettings stored, AiUsageLimits limits) {
        boolean managedProvider = provider.id().equals(environmentProvider());
        Managed managed = managedProvider ? managed() : Managed.NONE;
        StoredConnection saved = stored.connection(provider.id()).orElse(StoredConnection.EMPTY);
        URI baseUrl = baseUrl(provider, saved, managedProvider);
        if (baseUrl == null) {
            return new AiConnectionState(Readiness.BASE_URL_INVALID, provider, Optional.empty(), managed, limits);
        }
        String key = managedProvider && environmentKey() != null ? environmentKey() : storedKeyFor(saved, baseUrl);
        String model = managedProvider && environment.model() != null ? environment.model() : saved.model();
        AiConnection connection = new AiConnection(provider, baseUrl, key, model, null);
        connection = connection.withShape(shapeFor(saved, connection));
        return new AiConnectionState(readiness(provider, connection), provider, Optional.of(connection), managed, limits);
    }

    @Nullable
    private URI baseUrl(AiProviderDefinition provider, StoredConnection saved, boolean managedProvider) {
        if (managedProvider && environment.baseUrl() != null) {
            return baseUrlPolicy.check(environment.baseUrl()).validUrl().orElse(null);
        }
        if (managedProvider && environmentKey() != null) {
            return provider.defaultBaseUrl().orElse(null);
        }
        if (saved.baseUrl() != null) {
            return baseUrlPolicy.check(saved.baseUrl()).validUrl()
                .filter(url -> provider.editableBaseUrl() || provider.offers(url))
                .orElse(null);
        }
        return provider.defaultBaseUrl().orElse(null);
    }

    @Nullable
    private static String storedKeyFor(StoredConnection saved, URI baseUrl) {
        return saved.apiKey() != null && Objects.equals(saved.baseUrl(), baseUrl.toString()) ? saved.apiKey() : null;
    }

    @Nullable
    private static AiRequestShape shapeFor(StoredConnection saved, AiConnection connection) {
        if (saved.requestShape() == null || !connection.fingerprint().equals(saved.shapeFingerprint())) {
            return null;
        }
        return saved.requestShape().toShape().orElse(null);
    }

    private static Readiness readiness(AiProviderDefinition provider, AiConnection connection) {
        if (provider.requiresKey() && connection.apiKey() == null) {
            return Readiness.KEY_MISSING;
        }
        if (connection.apiKey() != null && !AiCredentialRules.validKey(connection.apiKey())) {
            return Readiness.KEY_MISSING;
        }
        if (!AiCredentialRules.validModel(provider, connection.model())) {
            return Readiness.MODEL_MISSING;
        }
        return Readiness.READY;
    }

    @Nullable
    private String environmentKey() {
        if (environment.apiKey() != null) {
            return environment.apiKey();
        }
        return usesGeminiAlias() ? environment.geminiApiKey() : null;
    }

    private boolean usesGeminiAlias() {
        return environment.apiKey() == null && environment.geminiApiKey() != null
            && (environment.provider() == null || AiProviderCatalog.GEMINI.equals(environment.provider()));
    }

    private AiConnectionState failed(Readiness readiness, AiUsageLimits limits) {
        return new AiConnectionState(readiness, null, Optional.empty(), managed(), limits);
    }
}

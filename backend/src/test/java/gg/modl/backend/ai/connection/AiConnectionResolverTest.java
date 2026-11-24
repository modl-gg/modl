package gg.modl.backend.ai.connection;

import static org.assertj.core.api.Assertions.assertThat;

import gg.modl.backend.ai.connection.AiProviderSettings.StoredConnection;
import gg.modl.backend.ai.http.AiAddressPolicy;
import gg.modl.backend.ai.http.AiBaseUrlPolicy;
import gg.modl.backend.ai.model.AiConnection;
import gg.modl.backend.ai.model.AiConnectionState;
import gg.modl.backend.ai.model.AiConnectionState.Readiness;
import gg.modl.backend.ai.provider.AiProviderCatalog;
import java.net.URI;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AiConnectionResolverTest {
    private static final String OPENAI_URL = "https://api.openai.com/v1";
    private static final StoredConnection STORED_OPENAI = new StoredConnection(OPENAI_URL, "stored-key", "stored-model", null, null, null);

    @Test
    void nothingConfiguredIsNotConfigured() {
        assertThat(resolve(AiEnvironmentProperties.NONE, AiProviderSettings.EMPTY).readiness()).isEqualTo(Readiness.NOT_CONFIGURED);
    }

    @Test
    void theStoredActiveConnectionIsUsedWithoutEnvironment() {
        AiConnectionState state = resolve(AiEnvironmentProperties.NONE, stored("openai", STORED_OPENAI));

        assertThat(state.readiness()).isEqualTo(Readiness.READY);
        assertThat(state.ready().map(AiConnection::apiKey)).contains("stored-key");
        assertThat(state.managed().any()).isFalse();
    }

    @Test
    void readinessReportsTheFirstMissingPiece() {
        assertThat(resolve(AiEnvironmentProperties.NONE,
            stored("openai", new StoredConnection(OPENAI_URL, null, "m", null, null, null))).readiness()).isEqualTo(Readiness.KEY_MISSING);
        assertThat(resolve(AiEnvironmentProperties.NONE,
            stored("openai", new StoredConnection(OPENAI_URL, "k", null, null, null, null))).readiness()).isEqualTo(Readiness.MODEL_MISSING);
        assertThat(resolve(AiEnvironmentProperties.NONE,
            stored("custom", new StoredConnection(null, null, "m", null, null, null))).readiness()).isEqualTo(Readiness.BASE_URL_INVALID);
        assertThat(resolve(AiEnvironmentProperties.NONE,
            stored("ollama", new StoredConnection("http://localhost:11434/v1", null, "llama", null, null, null))).readiness())
            .isEqualTo(Readiness.READY);
    }

    @Test
    void environmentValuesWithoutAProviderAreAConfigurationError() {
        AiConnectionState state = resolve(env(null, "k", null, null, null), stored("openai", STORED_OPENAI));

        assertThat(state.readiness()).isEqualTo(Readiness.CONFIG_PROVIDER_MISSING);
        assertThat(state.connection()).isEmpty();
    }

    @Test
    void anUnknownEnvironmentProviderIsReportedAndNeverFailsStartup() {
        assertThat(resolve(env("acme", "k", "m", null, null), AiProviderSettings.EMPTY).readiness())
            .isEqualTo(Readiness.CONFIG_PROVIDER_UNKNOWN);
    }

    @Test
    void anEnvironmentKeyLocksTheBaseUrlToThePreset() {
        StoredConnection elsewhere = new StoredConnection("https://evil.example/v1", "stored-key", "m", null, null, null);
        AiConnectionState state = resolve(env("openai", "env-key", null, null, null), stored("openai", elsewhere));

        AiConnection connection = state.ready().orElseThrow();
        assertThat(connection.baseUrl()).isEqualTo(URI.create(OPENAI_URL));
        assertThat(connection.apiKey()).isEqualTo("env-key");
        assertThat(connection.model()).isEqualTo("m");
        assertThat(state.managed().baseUrl()).isTrue();
        assertThat(state.managed().apiKey()).isTrue();
        assertThat(state.managed().model()).isFalse();
    }

    @Test
    void anEnvironmentUrlUsesAStoredKeyOnlyForTheSameUrl() {
        StoredConnection local = new StoredConnection("http://10.0.0.5:8000/v1", "stored-key", "m", null, null, null);

        assertThat(resolve(env("custom", null, null, "http://10.0.0.5:8000/v1", null), stored("custom", local))
            .ready().map(AiConnection::apiKey)).contains("stored-key");
        assertThat(resolve(env("custom", null, null, "http://10.0.0.6:8000/v1", null), stored("custom", local))
            .connection().map(AiConnection::apiKey)).isEmpty();
    }

    @Test
    void anEnvironmentUrlStillPassesTheAddressPolicy() {
        assertThat(resolve(env("custom", null, "m", "http://169.254.169.254/v1", null), AiProviderSettings.EMPTY).readiness())
            .isEqualTo(Readiness.BASE_URL_INVALID);
    }

    @Test
    void theEnvironmentModelLocksTheModel() {
        AiConnectionState state = resolve(env("openai", null, "env-model", null, null), stored("openai", STORED_OPENAI));

        assertThat(state.ready().map(AiConnection::model)).contains("env-model");
        assertThat(state.managed().model()).isTrue();
    }

    @Test
    void theEnvironmentProviderReplacesTheStoredActiveOne() {
        AiConnectionState state = resolve(env("anthropic", "env-key", "claude", null, null), stored("openai", STORED_OPENAI));

        assertThat(state.provider().id()).isEqualTo("anthropic");
        assertThat(state.managed().provider()).isTrue();
    }

    @Test
    void theGeminiAliasImpliesGeminiOnlyWithoutAnotherKey() {
        assertThat(resolve(env(null, null, null, null, "gem-key"), AiProviderSettings.EMPTY).provider().id()).isEqualTo("gemini");
        assertThat(resolve(env(null, null, null, null, "gem-key"), AiProviderSettings.EMPTY).readiness())
            .isEqualTo(Readiness.MODEL_MISSING);
        assertThat(resolve(env("gemini", null, "g", null, "gem-key"), AiProviderSettings.EMPTY).ready().map(AiConnection::apiKey))
            .contains("gem-key");
        assertThat(resolve(env("openai", null, "m", null, "gem-key"), AiProviderSettings.EMPTY).readiness())
            .isEqualTo(Readiness.KEY_MISSING);
        assertThat(resolve(env("gemini", "real-key", "g", null, "gem-key"), AiProviderSettings.EMPTY).ready().map(AiConnection::apiKey))
            .contains("real-key");
    }

    @Test
    void aStoredShapeAppliesOnlyToTheConnectionItWasVerifiedFor() {
        AiConnection verified = resolve(AiEnvironmentProperties.NONE, stored("openai", STORED_OPENAI)).ready().orElseThrow();
        StoredConnection withShape = STORED_OPENAI.withShape(verified.provider().initialShape().withoutEffortHint(),
            verified.fingerprint(), 1L);

        assertThat(resolve(AiEnvironmentProperties.NONE, stored("openai", withShape)).ready().orElseThrow().shape()).isNotNull();
        assertThat(resolve(env("openai", null, "other-model", null, null), stored("openai", withShape)).ready().orElseThrow().shape())
            .isNull();
    }

    private static AiConnectionState resolve(AiEnvironmentProperties environment, AiProviderSettings stored) {
        return new AiConnectionResolver(environment, new AiProviderCatalog(), new AiBaseUrlPolicy(new AiAddressPolicy())).resolve(stored);
    }

    private static AiEnvironmentProperties env(String provider, String key, String model, String baseUrl, String geminiKey) {
        return new AiEnvironmentProperties(provider, key, model, baseUrl, geminiKey);
    }

    private static AiProviderSettings stored(String active, StoredConnection connection) {
        return new AiProviderSettings(active, Map.of(active, connection), null, 0L);
    }
}

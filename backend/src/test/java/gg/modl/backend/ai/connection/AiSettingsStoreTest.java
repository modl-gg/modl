package gg.modl.backend.ai.connection;

import static org.assertj.core.api.Assertions.assertThat;

import gg.modl.backend.ai.connection.AiProviderSettings.StoredConnection;
import gg.modl.backend.instance.InstanceTestKit;
import gg.modl.backend.instance.ParallelCalls;
import gg.modl.backend.settings.data.SettingsType;
import gg.modl.backend.support.sql.DialectTarget;
import gg.modl.backend.support.sql.RetryInjection;
import gg.modl.backend.support.sql.SqlContractTest;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.io.TempDir;

class AiSettingsStoreTest {
    @TempDir
    Path dataDirectory;

    @SqlContractTest
    void anAbsentRowReadsAsNotConfigured(DialectTarget target) {
        try (InstanceTestKit kit = InstanceTestKit.start(target, dataDirectory)) {
            AiSettingsStore store = new AiSettingsStore(kit.documents(), kit.unitOfWork());

            assertThat(store.current()).isEqualTo(AiProviderSettings.EMPTY);
            assertThat(kit.version(SettingsType.AI_PROVIDER.id())).isZero();
        }
    }

    @SqlContractTest
    void updatesSurviveAnInjectedRetryAndPublishTheCommittedValue(DialectTarget target) {
        RetryInjection retries = new RetryInjection();
        try (InstanceTestKit kit = InstanceTestKit.start(target, dataDirectory, retries)) {
            AiSettingsStore store = new AiSettingsStore(kit.documents(), kit.unitOfWork());

            store.update(current -> current.withConnection("openai", connection("first")));
            store.update(current -> current.withConnection("anthropic", connection("second")).withActiveProvider("anthropic"));

            assertThat(retries.injected()).isPositive();
            assertThat(store.current().connections()).containsOnlyKeys("openai", "anthropic");
            assertThat(store.current().activeProvider()).isEqualTo("anthropic");
            assertThat(new AiSettingsStore(kit.documents(), kit.unitOfWork()).current()).isEqualTo(store.current());
        }
    }

    @SqlContractTest
    void concurrentWritersNeverLoseAConnection(DialectTarget target) throws Exception {
        try (InstanceTestKit kit = InstanceTestKit.start(target, dataDirectory)) {
            AiSettingsStore store = new AiSettingsStore(kit.documents(), kit.unitOfWork());
            List<String> providers = List.of("openai", "anthropic", "gemini", "deepseek");

            List<ParallelCalls.Outcome<AiProviderSettings>> outcomes = ParallelCalls.run(providers.size(),
                index -> store.update(current -> current.withConnection(providers.get(index), connection(providers.get(index)))));

            assertThat(outcomes).allMatch(ParallelCalls.Outcome::succeeded);
            assertThat(new AiSettingsStore(kit.documents(), kit.unitOfWork()).current().connections())
                .containsOnlyKeys(providers.toArray(String[]::new));
            assertThat(store.current().connections()).hasSize(providers.size());
        }
    }

    @SqlContractTest
    void unknownProviderEntriesArePreservedOnWrite(DialectTarget target) {
        try (InstanceTestKit kit = InstanceTestKit.start(target, dataDirectory)) {
            AiSettingsStore store = new AiSettingsStore(kit.documents(), kit.unitOfWork());
            store.update(current -> current.withConnection("future-vendor", connection("kept")));

            store.update(current -> current.withConnection("openai", connection("new")));

            assertThat(store.current().connection("future-vendor")).isPresent();
        }
    }

    private static StoredConnection connection(String key) {
        return new StoredConnection("https://api.openai.com/v1", key, "m", null, null, null);
    }
}

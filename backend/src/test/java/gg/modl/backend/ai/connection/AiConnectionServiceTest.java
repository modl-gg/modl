package gg.modl.backend.ai.connection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import gg.modl.backend.ai.connection.AiSettingsView.ConnectionInfo;
import gg.modl.backend.ai.http.AiAddressPolicy;
import gg.modl.backend.ai.http.AiBaseUrlPolicy;
import gg.modl.backend.ai.model.AiConnection;
import gg.modl.backend.ai.model.AiConnectionState.Readiness;
import gg.modl.backend.ai.model.AiRequestShape;
import gg.modl.backend.ai.model.AiRuntime;
import gg.modl.backend.ai.model.AiUsageLimits;
import gg.modl.backend.ai.protocol.AiShapeMemory;
import gg.modl.backend.database.StaleClientVersionException;
import gg.modl.backend.ai.provider.AiProviderCatalog;
import gg.modl.backend.infrastructure.exception.ConflictException;
import gg.modl.backend.infrastructure.exception.ValidationException;
import gg.modl.backend.instance.InstanceTestKit;
import gg.modl.backend.log.service.PanelActionAuditor;
import gg.modl.backend.settings.data.SettingsType;
import gg.modl.backend.support.sql.DialectTarget;
import gg.modl.backend.support.sql.SqlContractTest;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.mockito.ArgumentCaptor;
import org.junit.jupiter.api.io.TempDir;

class AiConnectionServiceTest {
    private static final String OPENAI = "openai";
    private static final String MODERATION_SETTINGS = "aiModerationSettings";

    private static final String OWNER = "owner@example.com";

    private final AiRuntime runtime = mock(AiRuntime.class);
    private final PanelActionAuditor auditor = mock(PanelActionAuditor.class);
    private final AiShapeMemory memory = new AiShapeMemory();

    @TempDir
    Path dataDirectory;

    @SqlContractTest
    void savingAndActivatingACompleteConnectionMakesItReady(DialectTarget target) {
        run(target, AiEnvironmentProperties.NONE, (kit, service) -> {
            service.saveConnection(OWNER, OPENAI, new AiConnectionInput(null, "sk-1", "model-a", true));

            assertThat(service.state().readiness()).isEqualTo(Readiness.READY);
            assertThat(service.view().activeProvider()).isEqualTo(OPENAI);
            verify(runtime).reset();
        });
    }

    @SqlContractTest
    void activationWithoutAModelIsRefusedAndNothingIsSaved(DialectTarget target) {
        run(target, AiEnvironmentProperties.NONE, (kit, service) -> {
            assertCode(() -> service.saveConnection(OWNER, OPENAI, new AiConnectionInput(null, "sk-1", null, true)), AiConnectionService.NOT_READY);

            assertThat(kit.version(SettingsType.AI_PROVIDER.id())).isZero();
            verify(runtime, never()).reset();
        });
    }

    @SqlContractTest
    void anAbsentKeyIsKeptOnlyWhileTheBaseUrlStaysTheSame(DialectTarget target) {
        run(target, AiEnvironmentProperties.NONE, (kit, service) -> {
            service.saveConnection(OWNER, "custom", new AiConnectionInput("http://10.0.0.5:8000/v1", "secret", "m", false));
            service.saveConnection(OWNER, "custom", new AiConnectionInput(null, null, "m2", false));
            assertThat(info(service, "custom").hasApiKey()).isTrue();

            service.saveConnection(OWNER, "custom", new AiConnectionInput("http://10.0.0.6:8000/v1", null, null, false));

            assertThat(info(service, "custom").hasApiKey()).isFalse();
            assertThat(info(service, "custom").model()).isEqualTo("m2");
        });
    }

    @SqlContractTest
    void emptyValuesClearTheKeyAndModel(DialectTarget target) {
        run(target, AiEnvironmentProperties.NONE, (kit, service) -> {
            service.saveConnection(OWNER, OPENAI, new AiConnectionInput(null, "sk-1", "m", false));

            service.saveConnection(OWNER, OPENAI, new AiConnectionInput(null, "", "", false));

            assertThat(info(service, OPENAI).hasApiKey()).isFalse();
            assertThat(info(service, OPENAI).model()).isNull();
        });
    }

    @SqlContractTest
    void inputsAreValidated(DialectTarget target) {
        run(target, AiEnvironmentProperties.NONE, (kit, service) -> {
            assertCode(() -> service.saveConnection(OWNER, "acme", new AiConnectionInput(null, null, null, false)),
                AiConnectionService.PROVIDER_UNKNOWN);
            assertCode(() -> service.saveConnection(OWNER, OPENAI, new AiConnectionInput("https://proxy.example/v1", null, null, false)),
                AiBaseUrlPolicy.INVALID);
            assertCode(() -> service.saveConnection(OWNER, "custom", new AiConnectionInput("http://169.254.169.254/v1", null, null, false)),
                AiBaseUrlPolicy.BLOCKED);
            assertCode(() -> service.saveConnection(OWNER, "custom", new AiConnectionInput("http://8.8.8.8/v1", null, null, false)),
                AiBaseUrlPolicy.HTTP_PUBLIC);
            assertCode(() -> service.saveConnection(OWNER, OPENAI, new AiConnectionInput(null, "bad key\r\nX: y", null, false)),
                AiCredentialRules.KEY_INVALID);
            assertCode(() -> service.saveConnection(OWNER, "gemini", new AiConnectionInput(null, null, "../models", false)),
                AiCredentialRules.MODEL_INVALID);
            assertCode(() -> service.updateSettings(OWNER, null, new AiLimitsWrite(0, new AiUsageLimits(0, 1, 1, 1, 4096))), AiConnectionService.LIMITS_INVALID);
        });
    }

    @SqlContractTest
    void limitsAreWrittenOnlyAgainstTheVersionTheyWereEditedFrom(DialectTarget target) {
        run(target, AiEnvironmentProperties.NONE, (kit, service) -> {
            AiUsageLimits first = new AiUsageLimits(30, 300, 3, 1, 2048);
            AiUsageLimits second = new AiUsageLimits(40, 400, 4, 2, 4096);

            assertThat(service.updateSettings(OWNER, null, new AiLimitsWrite(0, first)).limitsVersion()).isEqualTo(1);
            service.saveConnection(OWNER, OPENAI, new AiConnectionInput(null, "sk", "m", false));
            assertThat(service.view().limitsVersion()).isEqualTo(1);

            assertThatThrownBy(() -> service.updateSettings(OWNER, null, new AiLimitsWrite(0, second)))
                .isInstanceOf(StaleClientVersionException.class)
                .hasFieldOrPropertyWithValue("currentVersion", 1L);
            assertThat(service.view().limits()).isEqualTo(first);

            AiSettingsView updated = service.updateSettings(OWNER, null, new AiLimitsWrite(1, second));
            assertThat(updated.limits()).isEqualTo(second);
            assertThat(updated.limitsVersion()).isEqualTo(2);
            assertThat(service.updateSettings(OWNER, null, new AiLimitsWrite(2, second)).limitsVersion()).isEqualTo(2);
        });
    }

    @SqlContractTest
    void configurationManagedFieldsCannotBeWritten(DialectTarget target) {
        AiEnvironmentProperties managed = new AiEnvironmentProperties(OPENAI, "env-key", null, null, null);
        run(target, managed, (kit, service) -> {
            service.saveConnection(OWNER, OPENAI, new AiConnectionInput(null, null, "m", false));

            assertThatThrownBy(() -> service.saveConnection(OWNER, OPENAI, new AiConnectionInput(null, "other", null, false)))
                .isInstanceOf(ConflictException.class);
            assertThatThrownBy(() -> service.saveConnection(OWNER, "anthropic", new AiConnectionInput(null, "k", "m", false)))
                .isInstanceOf(ConflictException.class);
            assertThatThrownBy(() -> service.updateSettings(OWNER, "", null)).isInstanceOf(ConflictException.class);
            assertThatThrownBy(() -> service.deleteConnection(OWNER, OPENAI)).isInstanceOf(ConflictException.class);
            assertThat(service.state().readiness()).isEqualTo(Readiness.READY);
            assertThat(info(service, OPENAI).hasApiKey()).isTrue();
        });
    }

    @SqlContractTest
    void changingTheConnectionNeverTouchesTheModerationSettings(DialectTarget target) {
        run(target, AiEnvironmentProperties.NONE, (kit, service) -> {
            kit.store(MODERATION_SETTINGS, Map.of("enableAIReview", true, "enableAutomatedActions", true));
            long before = kit.version(MODERATION_SETTINGS);

            service.saveConnection(OWNER, OPENAI, new AiConnectionInput(null, "sk", "a", true));
            service.saveConnection(OWNER, OPENAI, new AiConnectionInput(null, null, "b", false));
            service.updateSettings(OWNER, "", null);

            assertThat(kit.version(MODERATION_SETTINGS)).isEqualTo(before);
            assertThat(kit.stored(MODERATION_SETTINGS)).containsEntry("enableAutomatedActions", true);
        });
    }

    @SqlContractTest
    void aChangedConnectionTakesItsShapeFromARecentTest(DialectTarget target) {
        run(target, AiEnvironmentProperties.NONE, (kit, service) -> {
            AiConnection tested = service.draft(new AiDraft(OPENAI, null, "sk", "m"), true);
            AiRequestShape shape = new AiRequestShape(AiRequestShape.OutputMode.JSON_OBJECT,
                AiRequestShape.TokenField.MAX_COMPLETION_TOKENS, false);
            memory.remember(tested, shape, Instant.parse("2026-11-22T10:00:00Z"));

            service.saveConnection(OWNER, OPENAI, new AiConnectionInput(null, "sk", "m", true));

            assertThat(service.state().ready().orElseThrow().shape()).isEqualTo(shape);
            assertThat(info(service, OPENAI).verifiedAt()).isEqualTo(Instant.parse("2026-11-22T10:00:00Z"));
        });
    }

    @SqlContractTest
    void recordingAShapeAppliesOnlyToTheEffectiveConnection(DialectTarget target) {
        run(target, AiEnvironmentProperties.NONE, (kit, service) -> {
            service.saveConnection(OWNER, OPENAI, new AiConnectionInput(null, "sk", "m", true));
            AiConnection effective = service.state().ready().orElseThrow();
            AiRequestShape shape = effective.provider().initialShape().withoutEffortHint();

            service.recordShape(effective.withShape(null), shape, null);
            service.recordShape(new AiConnection(effective.provider(), effective.baseUrl(), "other", "m", null),
                shape.withOutputMode(AiRequestShape.OutputMode.PROMPT_ONLY), null);

            assertThat(service.state().ready().orElseThrow().shape()).isEqualTo(shape);
        });
    }

    @SqlContractTest
    void draftsUseAStoredKeyOnlyForTheStoredUrl(DialectTarget target) {
        run(target, AiEnvironmentProperties.NONE, (kit, service) -> {
            service.saveConnection(OWNER, "custom", new AiConnectionInput("http://10.0.0.5:8000/v1", "secret", "m", false));

            assertThat(service.draft(new AiDraft("custom", null, null, null), false).apiKey()).isEqualTo("secret");
            assertThat(service.draft(new AiDraft("custom", "http://10.0.0.6:8000/v1", null, null), false).apiKey()).isNull();
        });
    }

    @SqlContractTest
    void anEnvironmentKeyIsNeverSentToADraftUrl(DialectTarget target) {
        AiEnvironmentProperties managed = new AiEnvironmentProperties("custom", "env-key", "m", "http://10.0.0.5:8000/v1", null);
        run(target, managed, (kit, service) -> {
            assertThat(service.draft(new AiDraft("custom", null, null, null), false).apiKey()).isEqualTo("env-key");
            assertThat(service.draft(new AiDraft("custom", "http://10.0.0.9:8000/v1", null, null), false).apiKey()).isNull();
        });
    }

    @SqlContractTest
    void aPanelKeyIsBoundToTheUrlTheConfigurationSets(DialectTarget target) {
        AiEnvironmentProperties managed = new AiEnvironmentProperties(OPENAI, null, "m", "https://gateway.example/v1", null);
        run(target, managed, (kit, service) -> {
            service.saveConnection(OWNER, OPENAI, new AiConnectionInput(null, "sk-1", null, true));

            assertThat(service.state().readiness()).isEqualTo(Readiness.READY);
            assertThat(service.state().ready().orElseThrow().apiKey()).isEqualTo("sk-1");
            assertThat(service.draft(new AiDraft(OPENAI, "https://gateway.example/v1", null, null), true).apiKey()).isEqualTo("sk-1");
        });
    }

    @SqlContractTest
    void deletingTheActiveConnectionDeactivatesIt(DialectTarget target) {
        run(target, AiEnvironmentProperties.NONE, (kit, service) -> {
            service.saveConnection(OWNER, OPENAI, new AiConnectionInput(null, "sk", "m", true));

            service.deleteConnection(OWNER, OPENAI);

            assertThat(service.state().readiness()).isEqualTo(Readiness.NOT_CONFIGURED);
            assertThat(service.view().connections()).isEmpty();
        });
    }

    @SqlContractTest
    void connectionChangesAreAuditedWithoutTheKey(DialectTarget target) {
        run(target, AiEnvironmentProperties.NONE, (kit, service) -> {
            service.saveConnection(OWNER, OPENAI, new AiConnectionInput(null, "sk-audited-secret", "m", true));
            service.saveConnection(OWNER, OPENAI, new AiConnectionInput(null, null, null, false));
            service.deleteConnection(OWNER, OPENAI);

            ArgumentCaptor<String> descriptions = ArgumentCaptor.forClass(String.class);
            verify(auditor, times(2)).recordStaffAction(any(), eq(OWNER), descriptions.capture());
            assertThat(descriptions.getAllValues().get(0)).contains("API key added", "model set to m", "made the active provider")
                .doesNotContain("sk-audited-secret");
            assertThat(descriptions.getAllValues().get(1)).startsWith("Removed the");
        });
    }

    @SqlContractTest
    void noViewEverCarriesTheKey(DialectTarget target) {
        run(target, AiEnvironmentProperties.NONE, (kit, service) -> {
            service.saveConnection(OWNER, OPENAI, new AiConnectionInput(null, "sk-very-secret", "m", true));

            assertThat(service.view().toString()).doesNotContain("sk-very-secret");
            assertThat(service.state().toString()).doesNotContain("sk-very-secret");
            assertThat(service.status().toString()).doesNotContain("sk-very-secret");
        });
    }

    private void run(DialectTarget target, AiEnvironmentProperties environment, KitConsumer test) {
        try (InstanceTestKit kit = InstanceTestKit.start(target, dataDirectory.resolve(String.valueOf(System.nanoTime())))) {
            AiConnectionService service = new AiConnectionService(environment, new AiSettingsStore(kit.documents(), kit.unitOfWork()),
                new AiProviderCatalog(), new AiBaseUrlPolicy(new AiAddressPolicy()), memory, runtime, kit.unitOfWork(), auditor,
                kit.instanceContext());
            test.accept(kit, service);
        }
    }

    private static ConnectionInfo info(AiConnectionService service, String provider) {
        return service.view().connections().stream().filter(info -> info.provider().equals(provider)).findFirst().orElseThrow();
    }

    private static void assertCode(ThrowingCallable call, String code) {
        assertThatThrownBy(call).isInstanceOf(ValidationException.class).hasFieldOrPropertyWithValue("machineCode", code);
    }

    @FunctionalInterface
    private interface KitConsumer {
        void accept(InstanceTestKit kit, AiConnectionService service);
    }
}

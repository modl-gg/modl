package gg.modl.backend.settings.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import gg.modl.backend.database.ConcurrentUpdateException;
import gg.modl.backend.database.StaleClientVersionException;
import gg.modl.backend.database.TransactionalUnitOfWork;
import gg.modl.backend.instance.InstanceTestKit;
import gg.modl.backend.instance.ParallelCalls;
import gg.modl.backend.instance.ParallelCalls.Outcome;
import gg.modl.backend.settings.data.AIModerationSettings;
import gg.modl.backend.settings.data.WebhookSettings;
import gg.modl.backend.settings.data.WebhookSettingsChange;
import gg.modl.backend.settings.repository.SettingsRepository;
import gg.modl.backend.support.sql.DialectTarget;
import gg.modl.backend.support.sql.RetryInjection;
import gg.modl.backend.support.sql.SqlContractTest;
import gg.modl.backend.webhook.DiscordWebhookDelivery;
import gg.modl.backend.webhook.WebhookDeliveryStatus;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.io.TempDir;

class SettingsVersioningContractTest {
    private static final String TYPE = "example";
    private static final String COUNT = "count";

    @TempDir
    Path dataDirectory;

    @SqlContractTest
    void documentsRoundTripNestedValuesUnicodeAndKeysWithDotsAndDollars(DialectTarget target) {
        try (InstanceTestKit kit = InstanceTestKit.start(target, dataDirectory, new RetryInjection())) {
            Map<String, Object> document = Map.of("a.b", Map.of("$c", List.of(1, "zwei", true)), "name", "Grüße 🎮");

            SettingsDocumentService.RawSettingsState saved = kit.documents().updateExpecting(TYPE, 0, current -> document);

            assertThat(saved.data()).isEqualTo(document);
            assertThat(saved.version()).isEqualTo(1);
            assertThat(saved.updatedAt()).isEqualTo(kit.clock().instant());
            assertThat(kit.stored(TYPE)).isEqualTo(document);
        }
    }

    @SqlContractTest
    void anUnchangedDocumentKeepsItsVersionButStillRejectsAStaleOne(DialectTarget target) {
        try (InstanceTestKit kit = InstanceTestKit.start(target, dataDirectory, new RetryInjection())) {
            kit.documents().updateExpecting(TYPE, 0, current -> Map.of(COUNT, 1));

            assertThat(kit.documents().updateExpecting(TYPE, 1, UnaryOperator.identity()).version()).isEqualTo(1);
            assertThatThrownBy(() -> kit.documents().updateExpecting(TYPE, 0, UnaryOperator.identity()))
                .isInstanceOfSatisfying(StaleClientVersionException.class,
                    stale -> assertThat(stale.currentVersion()).isEqualTo(1));
        }
    }

    @SqlContractTest
    void aStaleClientVersionFailsAfterExactlyOneAttempt(DialectTarget target) {
        try (InstanceTestKit kit = InstanceTestKit.start(target, dataDirectory, new RetryInjection())) {
            kit.store(TYPE, Map.of(COUNT, 1));
            SettingsRepository repository = spy(kit.settingsRepository());

            assertThatThrownBy(() -> documents(kit, repository).updateExpecting(TYPE, 0, current -> Map.of(COUNT, 2)))
                .isInstanceOf(StaleClientVersionException.class);

            verify(repository, times(1)).find(TYPE);
            verify(repository, never()).updateIfVersion(anyString(), anyLong(), anyString());
            assertThat(kit.stored(TYPE)).containsEntry(COUNT, 1);
        }
    }

    @SqlContractTest
    void aClientWriteThatLosesTheRaceIsNotRetried(DialectTarget target) {
        try (InstanceTestKit kit = InstanceTestKit.start(target, dataDirectory, new RetryInjection())) {
            kit.store(TYPE, Map.of(COUNT, 1));
            SettingsRepository repository = spy(kit.settingsRepository());
            doReturn(false).when(repository).updateIfVersion(eq(TYPE), anyLong(), anyString());

            assertThatThrownBy(() -> documents(kit, repository).updateExpecting(TYPE, 1, current -> Map.of(COUNT, 2)))
                .isInstanceOf(StaleClientVersionException.class);

            verify(repository, times(1)).updateIfVersion(eq(TYPE), anyLong(), anyString());
        }
    }

    @SqlContractTest
    void anInternalConflictIsRetriedAndSucceeds(DialectTarget target) {
        try (InstanceTestKit kit = InstanceTestKit.start(target, dataDirectory, new RetryInjection())) {
            kit.store(TYPE, Map.of(COUNT, 1));
            SettingsRepository repository = spy(kit.settingsRepository());
            doReturn(false).doCallRealMethod().when(repository).updateIfVersion(eq(TYPE), anyLong(), anyString());
            AtomicInteger attempts = new AtomicInteger();

            SettingsDocumentService.RawSettingsState saved = documents(kit, repository).updateLatest(TYPE, current -> {
                attempts.incrementAndGet();
                return incremented(current);
            });

            assertThat(attempts).hasValue(2);
            assertThat(saved.version()).isEqualTo(2);
            assertThat(kit.stored(TYPE)).containsEntry(COUNT, 2);
        }
    }

    @SqlContractTest
    void anInternalConflictSurfacesAfterTheLastAttempt(DialectTarget target) {
        try (InstanceTestKit kit = InstanceTestKit.start(target, dataDirectory, new RetryInjection())) {
            kit.store(TYPE, Map.of(COUNT, 1));
            SettingsRepository repository = spy(kit.settingsRepository());
            doReturn(false).when(repository).updateIfVersion(eq(TYPE), anyLong(), anyString());

            assertThatThrownBy(() -> documents(kit, repository).updateLatest(TYPE, SettingsVersioningContractTest::incremented))
                .isInstanceOf(ConcurrentUpdateException.class);

            verify(repository, times(TransactionalUnitOfWork.MAX_WRITE_ATTEMPTS)).updateIfVersion(eq(TYPE), anyLong(), anyString());
        }
    }

    @SqlContractTest
    void twoInternalWritersBothLandIncludingTheOneThatCreatesTheRow(DialectTarget target) throws Exception {
        try (InstanceTestKit kit = InstanceTestKit.start(target, dataDirectory, new RetryInjection())) {
            List<Outcome<SettingsDocumentService.RawSettingsState>> outcomes = ParallelCalls.run(2,
                caller -> kit.documents().updateLatest(TYPE, SettingsVersioningContractTest::incremented));

            assertThat(outcomes).allMatch(Outcome::succeeded);
            assertThat(kit.stored(TYPE)).containsEntry(COUNT, 2);
            assertThat(kit.version(TYPE)).isEqualTo(2);
        }
    }

    @SqlContractTest
    void twoFirstSavesFromClientsNeverOverwriteEachOther(DialectTarget target) throws Exception {
        try (InstanceTestKit kit = InstanceTestKit.start(target, dataDirectory, new RetryInjection())) {
            List<Outcome<SettingsDocumentService.RawSettingsState>> outcomes = ParallelCalls.run(2,
                caller -> kit.documents().updateExpecting(TYPE, 0, current -> Map.of(COUNT, caller)));

            assertExactlyOneWon(outcomes);
            assertThat(kit.version(TYPE)).isEqualTo(1);
        }
    }

    @SqlContractTest
    void twoWebhookSavesWithTheSameVersionLetOneWinAndRefuseTheOther(DialectTarget target) throws Exception {
        try (InstanceTestKit kit = InstanceTestKit.start(target, dataDirectory, new RetryInjection())) {
            WebhookSettingsService service = new WebhookSettingsService(kit.documents(), mock(DiscordWebhookDelivery.class),
                new WebhookDeliveryStatus());
            WebhookSettings loaded = service.getWebhookSettings(kit.server());
            long version = service.updateWebhookSettings(kit.server(), 0, new WebhookSettingsChange(loaded, null)).version();

            List<Outcome<VersionedSettings<WebhookSettings>>> outcomes = ParallelCalls.run(2, caller ->
                service.updateWebhookSettings(kit.server(), version,
                    new WebhookSettingsChange(WebhookSettings.builder().botName("bot-" + caller).build(), null)));

            assertExactlyOneWon(outcomes);
            VersionedSettings<WebhookSettings> winner =
                outcomes.stream().filter(Outcome::succeeded).map(Outcome::value).findFirst().orElseThrow();
            assertThat(service.getWebhookSettingsState(kit.server())).isEqualTo(winner);
            assertThat(winner.version()).isEqualTo(version + 1);
        }
    }

    @SqlContractTest
    void theWebhookUrlIsWriteOnlyAndKeptUnlessReplacedOrCleared(DialectTarget target) {
        try (InstanceTestKit kit = InstanceTestKit.start(target, dataDirectory, new RetryInjection())) {
            WebhookSettingsService service = new WebhookSettingsService(kit.documents(), mock(DiscordWebhookDelivery.class),
                new WebhookDeliveryStatus());
            String url = "https://discord.com/api/webhooks/1/secret-token";
            long version = service.updateWebhookSettings(kit.server(), 0,
                new WebhookSettingsChange(WebhookSettings.builder().botName("bot").build(), url)).version();

            version = service.updateWebhookSettings(kit.server(), version,
                new WebhookSettingsChange(WebhookSettings.builder().botName("renamed").build(), null)).version();
            assertThat(service.getWebhookSettings(kit.server()).getDiscordWebhookUrl()).isEqualTo(url);
            assertThat(service.getWebhookSettings(kit.server()).getBotName()).isEqualTo("renamed");

            service.updateWebhookSettings(kit.server(), version,
                new WebhookSettingsChange(WebhookSettings.builder().botName("renamed").build(), ""));
            assertThat(service.getWebhookSettings(kit.server()).getDiscordWebhookUrl()).isEmpty();
        }
    }

    @SqlContractTest
    void anAiModerationSaveWithAnOldVersionIsRefused(DialectTarget target) {
        try (InstanceTestKit kit = InstanceTestKit.start(target, dataDirectory, new RetryInjection())) {
            AIModerationSettingsService service = new AIModerationSettingsService(kit.documents(), mock(AiPunishmentEligibility.class));
            AIModerationSettings enabled = AIModerationSettings.builder().enableAIReview(true).build();
            service.updateAIModerationSettings(kit.server(), 0, enabled);

            assertThatThrownBy(() -> service.updateAIModerationSettings(kit.server(), 0, AIModerationSettings.builder().build()))
                .isInstanceOf(StaleClientVersionException.class);

            assertThat(service.getAIModerationSettings(kit.server()).isEnableAIReview()).isTrue();
            assertThat(service.getAIModerationSettingsState(kit.server()).version()).isEqualTo(1);
        }
    }

    @SqlContractTest
    void aDeletedDocumentReadsAsAbsentVersionZero(DialectTarget target) {
        try (InstanceTestKit kit = InstanceTestKit.start(target, dataDirectory, new RetryInjection())) {
            kit.store(TYPE, Map.of(COUNT, 1));

            kit.documents().deleteState(TYPE);

            assertThat(kit.documents().getRawState(TYPE).exists()).isFalse();
            assertThat(kit.version(TYPE)).isZero();
        }
    }

    private static void assertExactlyOneWon(List<? extends Outcome<?>> outcomes) {
        assertThat(outcomes.stream().filter(Outcome::succeeded).count()).isEqualTo(1);
        assertThat(outcomes.stream().filter(outcome -> !outcome.succeeded()).findFirst().orElseThrow().failure())
            .isInstanceOf(StaleClientVersionException.class);
    }

    private static Map<String, Object> incremented(Map<String, Object> current) {
        current.put(COUNT, ((Number) current.getOrDefault(COUNT, 0)).intValue() + 1);
        return current;
    }

    private static SettingsDocumentService documents(InstanceTestKit kit, SettingsRepository repository) {
        return new SettingsDocumentService(repository, kit.unitOfWork(), kit.database().jsonColumns());
    }
}

package gg.modl.backend.ai.usage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.spy;

import gg.modl.backend.ai.model.AiFailureKind;
import gg.modl.backend.ai.model.AiUsageLimits;
import gg.modl.backend.database.DatabaseBusyException;
import gg.modl.backend.instance.InstanceTestKit;
import gg.modl.backend.instance.ParallelCalls;
import gg.modl.backend.settings.service.SettingsDocumentService;
import gg.modl.backend.support.sql.DialectTarget;
import gg.modl.backend.support.sql.RetryInjection;
import gg.modl.backend.support.sql.SqlContractTest;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.io.TempDir;

class AiBudgetLedgerTest {
    private static final Instant NOON = Instant.parse("2026-11-22T12:10:00Z");
    private static final AiUsageLimits TWO_PER_HOUR = new AiUsageLimits(2, 3, 5, 2, 4096);

    @TempDir
    Path dataDirectory;

    @SqlContractTest
    void callsAreCountedUntilTheHourlyLimit(DialectTarget target) {
        try (InstanceTestKit kit = InstanceTestKit.start(target, dataDirectory, new RetryInjection())) {
            AiBudgetLedger ledger = new AiBudgetLedger(kit.documents(), kit.unitOfWork());

            assertThat(ledger.tryConsume(TWO_PER_HOUR, NOON)).isEmpty();
            assertThat(ledger.tryConsume(TWO_PER_HOUR, NOON.plusSeconds(60))).isEmpty();
            assertThat(ledger.tryConsume(TWO_PER_HOUR, NOON.plusSeconds(120))).contains(AiFailureKind.HOURLY_BUDGET);
            assertThat(ledger.current(NOON).hourCalls()).isEqualTo(2);
        }
    }

    @SqlContractTest
    void windowsRollOnFixedUtcBoundariesAndTheDayLimitHolds(DialectTarget target) {
        try (InstanceTestKit kit = InstanceTestKit.start(target, dataDirectory)) {
            AiBudgetLedger ledger = new AiBudgetLedger(kit.documents(), kit.unitOfWork());
            ledger.tryConsume(TWO_PER_HOUR, NOON);
            ledger.tryConsume(TWO_PER_HOUR, NOON);

            Instant nextHour = Instant.parse("2026-11-22T13:00:00Z");
            assertThat(ledger.tryConsume(TWO_PER_HOUR, nextHour)).isEmpty();
            assertThat(ledger.tryConsume(TWO_PER_HOUR, nextHour)).contains(AiFailureKind.DAILY_BUDGET);
            assertThat(ledger.tryConsume(TWO_PER_HOUR, Instant.parse("2026-11-23T00:00:00Z"))).isEmpty();
            assertThat(ledger.current(Instant.parse("2026-11-23T00:30:00Z")).dayCalls()).isEqualTo(1);
        }
    }

    @SqlContractTest
    void theCountSurvivesARestart(DialectTarget target) {
        try (InstanceTestKit kit = InstanceTestKit.start(target, dataDirectory)) {
            new AiBudgetLedger(kit.documents(), kit.unitOfWork()).tryConsume(TWO_PER_HOUR, NOON);

            AiBudgetLedger restarted = new AiBudgetLedger(kit.documents(), kit.unitOfWork());

            assertThat(restarted.current(NOON.plus(Duration.ofMinutes(5))).hourCalls()).isEqualTo(1);
        }
    }

    @SqlContractTest
    void concurrentCallersNeverExceedTheLimit(DialectTarget target) throws Exception {
        try (InstanceTestKit kit = InstanceTestKit.start(target, dataDirectory)) {
            AiBudgetLedger ledger = new AiBudgetLedger(kit.documents(), kit.unitOfWork());
            AiUsageLimits fivePerHour = new AiUsageLimits(5, 100, 5, 2, 4096);

            List<ParallelCalls.Outcome<Optional<AiFailureKind>>> outcomes = ParallelCalls.run(8, index -> ledger.tryConsume(fivePerHour, NOON));

            assertThat(outcomes).allMatch(ParallelCalls.Outcome::succeeded);
            assertThat(outcomes.stream().filter(outcome -> outcome.value().isEmpty())).hasSize(5);
            assertThat(ledger.current(NOON).hourCalls()).isEqualTo(5);
        }
    }

    @SqlContractTest
    void aDatabaseFailureFailsClosed(DialectTarget target) {
        try (InstanceTestKit kit = InstanceTestKit.start(target, dataDirectory)) {
            SettingsDocumentService documents = spy(kit.documents());
            doThrow(new DatabaseBusyException("busy", Duration.ofSeconds(1))).when(documents).updateLatest(any(), any());
            AiBudgetLedger ledger = new AiBudgetLedger(documents, kit.unitOfWork());

            assertThatThrownBy(() -> ledger.tryConsume(TWO_PER_HOUR, NOON)).isInstanceOf(DatabaseBusyException.class);
            assertThat(ledger.current(NOON).hourCalls()).isZero();
        }
    }
}

package gg.modl.backend.instance.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import gg.modl.backend.database.ValueTooLongException;
import gg.modl.backend.instance.InstanceSecrets;
import gg.modl.backend.instance.InstanceTestKit;
import gg.modl.backend.instance.ParallelCalls;
import gg.modl.backend.instance.ParallelCalls.Outcome;
import gg.modl.backend.instance.setup.IssuedSetupCode;
import gg.modl.backend.support.sql.DialectTarget;
import gg.modl.backend.support.sql.SqlContractTest;
import gg.modl.backend.support.sql.SqlTestDatabase;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.io.TempDir;

class InstanceSecretsRepositoryContractTest {
    private static final Instant NOW = SqlTestDatabase.START;
    private static final Instant EXPIRY = NOW.plus(Duration.ofHours(1));
    private static final int THRESHOLD = 50;
    private static final String HASH = "hash-of-the-code";

    @TempDir
    Path dataDirectory;

    @SqlContractTest
    void theGeneratedSecretsAreNeverReplaced(DialectTarget target) {
        try (InstanceTestKit kit = InstanceTestKit.start(target, dataDirectory)) {
            InstanceSecrets again = kit.write(() -> kit.secretsRepository().ensure("other-secret", "other-key", NOW.plusSeconds(5)));

            assertThat(again.getAuthCodeHashSecret()).isEqualTo("auth-secret");
            assertThat(again.getStorageSigningKey()).isEqualTo("signing-key");
            assertThat(again.getCreatedAt()).isEqualTo(NOW);
            assertThat(again.getSetupCodeFailedAttempts()).isZero();
            assertThat(again.getSetupCodeHash()).isNull();
        }
    }

    @SqlContractTest
    void aStoredCodeReplacesThePreviousOneAndClearsItsFailures(DialectTarget target) {
        try (InstanceTestKit kit = InstanceTestKit.start(target, dataDirectory)) {
            store(kit, new IssuedSetupCode("first", NOW, EXPIRY));
            kit.write(() -> kit.secretsRepository().recordFailedSetupCodeAttempt(NOW));

            store(kit, new IssuedSetupCode(HASH, NOW.plusSeconds(1), EXPIRY.plusSeconds(1)));

            InstanceSecrets stored = stored(kit);
            assertThat(stored.getSetupCodeHash()).isEqualTo(HASH);
            assertThat(stored.getSetupCodeIssuedAt()).isEqualTo(NOW.plusSeconds(1));
            assertThat(stored.getSetupCodeExpiresAt()).isEqualTo(EXPIRY.plusSeconds(1));
            assertThat(stored.getSetupCodeFailedAttempts()).isZero();
        }
    }

    @SqlContractTest
    void onlyTheExactActiveUnlockedCodeIsConsumed(DialectTarget target) {
        try (InstanceTestKit kit = InstanceTestKit.start(target, dataDirectory)) {
            store(kit, new IssuedSetupCode(HASH, NOW, EXPIRY));

            assertThat(consume(kit, "HASH-OF-THE-CODE", NOW, THRESHOLD)).isFalse();
            assertThat(consume(kit, HASH, EXPIRY, THRESHOLD)).isFalse();
            assertThat(consume(kit, HASH, NOW, 0)).isFalse();
            assertThat(stored(kit).getSetupCodeHash()).isEqualTo(HASH);

            assertThat(consume(kit, HASH, NOW, THRESHOLD)).isTrue();

            InstanceSecrets consumed = stored(kit);
            assertThat(consumed.getSetupCodeHash()).isNull();
            assertThat(consumed.getSetupCodeExpiresAt()).isNull();
            assertThat(consumed.getSetupCodeFailedAttempts()).isZero();
            assertThat(consumed.getSetupCodeIssuedAt()).isEqualTo(NOW);
        }
    }

    @SqlContractTest
    void eightConcurrentConsumersUseACodeOnce(DialectTarget target) throws Exception {
        try (InstanceTestKit kit = InstanceTestKit.start(target, dataDirectory)) {
            store(kit, new IssuedSetupCode(HASH, NOW, EXPIRY));

            List<Outcome<Boolean>> outcomes = ParallelCalls.run(8, caller -> consume(kit, HASH, NOW, THRESHOLD));

            assertThat(outcomes).allMatch(Outcome::succeeded);
            assertThat(outcomes).extracting(Outcome::value).containsOnlyOnce(true);
        }
    }

    @SqlContractTest
    void concurrentWrongCodesAreAllCounted(DialectTarget target) throws Exception {
        try (InstanceTestKit kit = InstanceTestKit.start(target, dataDirectory)) {
            store(kit, new IssuedSetupCode(HASH, NOW, EXPIRY));

            List<Outcome<Optional<Integer>>> outcomes = ParallelCalls.run(8, caller ->
                kit.write(() -> kit.secretsRepository().recordFailedSetupCodeAttempt(NOW)));

            assertThat(outcomes).allMatch(Outcome::succeeded);
            assertThat(outcomes.stream().map(outcome -> outcome.value().orElseThrow()).toList())
                .containsExactlyInAnyOrder(1, 2, 3, 4, 5, 6, 7, 8);
            assertThat(stored(kit).getSetupCodeFailedAttempts()).isEqualTo(8);
        }
    }

    @SqlContractTest
    void failuresAreNotCountedWithoutAnActiveCode(DialectTarget target) {
        try (InstanceTestKit kit = InstanceTestKit.start(target, dataDirectory)) {
            assertThat(kit.write(() -> kit.secretsRepository().recordFailedSetupCodeAttempt(NOW))).isEmpty();
            store(kit, new IssuedSetupCode(HASH, NOW, EXPIRY));

            assertThat(kit.write(() -> kit.secretsRepository().recordFailedSetupCodeAttempt(EXPIRY))).isEmpty();
            assertThat(stored(kit).getSetupCodeFailedAttempts()).isZero();
        }
    }

    @SqlContractTest
    void clearingRemovesTheCodeButKeepsTheIssueTime(DialectTarget target) {
        try (InstanceTestKit kit = InstanceTestKit.start(target, dataDirectory)) {
            store(kit, new IssuedSetupCode(HASH, NOW, EXPIRY));

            kit.unitOfWork().write(() -> kit.secretsRepository().clearSetupCode());

            assertThat(stored(kit).getSetupCodeHash()).isNull();
            assertThat(stored(kit).getSetupCodeExpiresAt()).isNull();
            assertThat(stored(kit).getSetupCodeIssuedAt()).isEqualTo(NOW);
            assertThat(stored(kit).getAuthCodeHashSecret()).isEqualTo("auth-secret");
        }
    }

    @SqlContractTest
    void aHashLongerThanItsColumnIsRefused(DialectTarget target) {
        try (InstanceTestKit kit = InstanceTestKit.start(target, dataDirectory)) {
            assertThatThrownBy(() -> store(kit, new IssuedSetupCode("h".repeat(256), NOW, EXPIRY)))
                .isInstanceOf(ValueTooLongException.class);
            assertThatThrownBy(() -> kit.write(() -> kit.secretsRepository().ensure("s".repeat(256), "key", NOW)))
                .isInstanceOf(ValueTooLongException.class);
        }
    }

    private static void store(InstanceTestKit kit, IssuedSetupCode code) {
        kit.unitOfWork().write(() -> kit.secretsRepository().storeSetupCode(code));
    }

    private static boolean consume(InstanceTestKit kit, String hash, Instant now, int threshold) {
        return kit.write(() -> kit.secretsRepository().consumeSetupCode(hash, now, threshold));
    }

    private static InstanceSecrets stored(InstanceTestKit kit) {
        return kit.secretsRepository().findSetupCode().orElseThrow();
    }
}

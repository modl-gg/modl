package gg.modl.backend.auth.repository;

import static gg.modl.backend.database.jooq.Tables.PASSWORD_RESET_TOKENS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import gg.modl.backend.database.ValueTooLongException;
import gg.modl.backend.instance.ParallelCalls;
import gg.modl.backend.support.sql.DialectTarget;
import gg.modl.backend.support.sql.SqlContractTest;
import gg.modl.backend.support.sql.SqlTestDatabase;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.io.TempDir;

class CredentialRepositoriesContractTest {
    private static final String EMAIL = "mod@example.com";
    private static final Instant NOW = SqlTestDatabase.START;
    private static final Instant LATER = NOW.plus(Duration.ofMinutes(10));

    @TempDir
    Path dataDirectory;

    @SqlContractTest
    void aPasswordIsInsertedOnceAndChangedOnlyFromTheExpectedHash(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory)) {
            StaffCredentialRepository passwords = AuthRepositories.on(database).passwords();

            assertThat(passwords.insertIfAbsent("Mod@Example.com", "first", NOW)).isTrue();
            assertThat(passwords.insertIfAbsent(EMAIL, "second", NOW)).isFalse();
            assertThat(passwords.findPasswordHash(EMAIL)).contains("first");

            assertThat(passwords.updateIfMatches(EMAIL, "stale", "third", NOW)).isFalse();
            assertThat(passwords.updateIfMatches(EMAIL, "first", "third", NOW)).isTrue();
            assertThat(passwords.findPasswordHash(EMAIL)).contains("third");

            passwords.replace(EMAIL, "fourth", NOW);
            passwords.replace("new@example.com", "fifth", NOW);
            assertThat(passwords.findEmails()).containsExactlyInAnyOrder(EMAIL, "new@example.com");

            passwords.delete(EMAIL);
            assertThat(passwords.findPasswordHash(EMAIL)).isEmpty();
            assertThatThrownBy(() -> passwords.replace(EMAIL, "h".repeat(256), NOW)).isInstanceOf(ValueTooLongException.class);
        }
    }

    @SqlContractTest
    void movingAPasswordReplacesAnOrphanAtTheTarget(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory)) {
            StaffCredentialRepository passwords = AuthRepositories.on(database).passwords();
            passwords.replace(EMAIL, "mine", NOW);
            passwords.replace("next@example.com", "orphan", NOW);

            database.unitOfWork().write(() -> passwords.move(EMAIL, "Next@Example.com", NOW));

            assertThat(passwords.findPasswordHash("next@example.com")).contains("mine");
            assertThat(passwords.findPasswordHash(EMAIL)).isEmpty();
        }
    }

    @SqlContractTest
    void aPendingSecretIsEnabledOnceAndItsStepNeverGoesBackwards(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory)) {
            StaffTotpRepository totp = AuthRepositories.on(database).totp();
            totp.savePending(EMAIL, "PENDINGSECRET", LATER, NOW);
            assertThat(totp.isEnabled(EMAIL)).isFalse();

            assertThat(totp.enablePending(EMAIL, "OTHERSECRET", 100, NOW)).isFalse();
            assertThat(totp.enablePending(EMAIL, "PENDINGSECRET", 100, LATER)).isFalse();
            assertThat(totp.enablePending(EMAIL, "PENDINGSECRET", 100, NOW)).isTrue();
            assertThat(totp.find(EMAIL)).get().satisfies(row -> {
                assertThat(row.secret()).isEqualTo("PENDINGSECRET");
                assertThat(row.pendingSecret()).isNull();
                assertThat(row.lastUsedStep()).isEqualTo(100L);
            });

            assertThat(totp.advanceStep(EMAIL, "PENDINGSECRET", 100)).isFalse();
            assertThat(totp.advanceStep(EMAIL, "PENDINGSECRET", 99)).isFalse();
            assertThat(totp.advanceStep(EMAIL, "WRONGSECRET", 101)).isFalse();
            assertThat(totp.advanceStep(EMAIL, "PENDINGSECRET", 101)).isTrue();
            assertThat(totp.findEnabledEmails()).containsExactly(EMAIL);
        }
    }

    @SqlContractTest
    void concurrentUsesOfOneStepAdmitExactlyOne(DialectTarget target) throws Exception {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory)) {
            StaffTotpRepository totp = AuthRepositories.on(database).totp();
            totp.savePending(EMAIL, "SECRET", LATER, NOW);
            totp.enablePending(EMAIL, "SECRET", 1, NOW);

            List<Boolean> outcomes = ParallelCalls.run(8, caller -> database.unitOfWork().write(() -> totp.advanceStep(EMAIL, "SECRET", 2)))
                .stream().map(ParallelCalls.Outcome::value).toList();

            assertThat(outcomes).containsOnlyOnce(true);
        }
    }

    @SqlContractTest
    void recoveryCodesAreReplacedConsumedOnceAndMovedWithTheAccount(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory)) {
            StaffTotpRepository totp = AuthRepositories.on(database).totp();
            totp.savePending(EMAIL, "SECRET", LATER, NOW);
            totp.replaceRecoveryCodes(EMAIL, List.of("a", "b", "c"), NOW);
            totp.replaceRecoveryCodes(EMAIL, List.of("d", "e"), NOW);
            assertThat(totp.countRecoveryCodes(EMAIL)).isEqualTo(2);

            assertThat(totp.consumeRecoveryCode(EMAIL, "a")).isFalse();
            assertThat(totp.consumeRecoveryCode(EMAIL, "d")).isTrue();
            assertThat(totp.consumeRecoveryCode(EMAIL, "d")).isFalse();
            assertThat(totp.consumeRecoveryCode("other@example.com", "e")).isFalse();

            totp.savePending("next@example.com", "ORPHAN", LATER, NOW);
            database.unitOfWork().write(() -> totp.move(EMAIL, "next@example.com"));
            assertThat(totp.countRecoveryCodes("next@example.com")).isEqualTo(1);
            assertThat(totp.find("next@example.com")).get().extracting(StaffTotp::pendingSecret).isEqualTo("SECRET");
            assertThat(totp.find(EMAIL)).isEmpty();

            totp.delete("next@example.com");
            assertThat(totp.countRecoveryCodes("next@example.com")).isZero();
        }
    }

    @SqlContractTest
    void aResetTokenIsOnePerAccountUsableOnceAndExpires(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory)) {
            PasswordResetTokenRepository tokens = AuthRepositories.on(database).resetTokens();
            tokens.replace(EMAIL, "first", NOW, LATER);
            tokens.replace(EMAIL, "second", NOW, LATER);

            assertThat(tokens.findActive("first", NOW)).isEmpty();
            assertThat(tokens.findActive("second", NOW)).get().extracting(PasswordResetToken::email).isEqualTo(EMAIL);
            assertThat(tokens.findActive("second", LATER)).isEmpty();
            assertThat(tokens.consumeActive("second", NOW)).isPresent();
            assertThat(tokens.consumeActive("second", NOW)).isEmpty();

            tokens.replace(EMAIL, "third", NOW, LATER);
            tokens.deleteByEmail("MOD@example.com");
            assertThat(database.dsl().fetchCount(PASSWORD_RESET_TOKENS)).isZero();
        }
    }
}

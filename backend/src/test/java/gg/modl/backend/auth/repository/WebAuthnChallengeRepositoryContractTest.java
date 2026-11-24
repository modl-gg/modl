package gg.modl.backend.auth.repository;

import static gg.modl.backend.database.jooq.Tables.WEBAUTHN_CHALLENGES;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import gg.modl.backend.auth.data.WebAuthnChallenge;
import gg.modl.backend.database.DuplicateRowException;
import gg.modl.backend.database.ValueTooLongException;
import gg.modl.backend.support.sql.DialectTarget;
import gg.modl.backend.support.sql.SqlContractTest;
import gg.modl.backend.support.sql.SqlTestDatabase;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.io.TempDir;

class WebAuthnChallengeRepositoryContractTest {
    private static final Instant NOW = SqlTestDatabase.START;
    private static final Instant EXPIRES = NOW.plusSeconds(300);

    @TempDir
    Path dataDirectory;

    @SqlContractTest
    void aChallengeRoundTripsWithOrWithoutAnEmail(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory)) {
            WebAuthnChallengeRepository challenges = AuthRepositories.on(database).challenges();
            String json = "{\"a.b\":{\"$key\":\"café 🚀\"},\"padding\":\"" + "x".repeat(20_000) + "\"}";
            WebAuthnChallenge bound = new WebAuthnChallenge("bound", json, "mod@example.com", EXPIRES);
            WebAuthnChallenge discoverable = new WebAuthnChallenge("discoverable", "{}", null, EXPIRES);

            challenges.insert(bound);
            challenges.insert(discoverable);

            assertThat(challenges.consumeActive("bound", NOW)).contains(bound);
            assertThat(challenges.consumeActive("discoverable", NOW)).contains(discoverable);
            assertThat(database.dsl().fetchCount(WEBAUTHN_CHALLENGES)).isZero();
        }
    }

    @SqlContractTest
    void aChallengeIsConsumedOnceUnderConcurrency(DialectTarget target) throws Exception {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory)) {
            WebAuthnChallengeRepository challenges = AuthRepositories.on(database).challenges();
            challenges.insert(new WebAuthnChallenge("once", "{}", null, EXPIRES));

            List<Boolean> consumed = SimultaneousCalls.run(SimultaneousCalls.CALLERS,
                caller -> challenges.consumeActive("once", NOW).isPresent());

            assertThat(consumed).containsOnlyOnce(true);
        }
    }

    @SqlContractTest
    void anExpiredUnknownOrOverlongIdIsNotConsumed(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory)) {
            WebAuthnChallengeRepository challenges = AuthRepositories.on(database).challenges();
            challenges.insert(new WebAuthnChallenge("challenge", "{}", null, EXPIRES));

            assertThat(challenges.consumeActive("challenge", EXPIRES)).isEmpty();
            assertThat(challenges.consumeActive("CHALLENGE", NOW)).isEmpty();
            assertThat(challenges.consumeActive(null, NOW)).isEmpty();
            assertThat(challenges.consumeActive("c".repeat(256), NOW)).isEmpty();
            assertThat(database.dsl().fetchCount(WEBAUTHN_CHALLENGES)).isEqualTo(1);
        }
    }

    @SqlContractTest
    void deletingByEmailTakesEveryChallengeOfThatEmailOnly(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory)) {
            WebAuthnChallengeRepository challenges = AuthRepositories.on(database).challenges();
            challenges.insert(new WebAuthnChallenge("live", "{}", "mod@example.com", EXPIRES));
            challenges.insert(new WebAuthnChallenge("expired", "{}", "mod@example.com", NOW));
            challenges.insert(new WebAuthnChallenge("theirs", "{}", "other@example.com", EXPIRES));
            challenges.insert(new WebAuthnChallenge("discoverable", "{}", null, EXPIRES));

            assertThat(challenges.deleteByEmail(" Mod@Example.com ")).isEqualTo(2);
            assertThat(challenges.deleteByEmail(" ")).isZero();
            assertThat(challenges.deleteByEmail(null)).isZero();

            assertThat(database.dsl().fetch(WEBAUTHN_CHALLENGES).getValues(WEBAUTHN_CHALLENGES.ID))
                .containsExactlyInAnyOrder("theirs", "discoverable");
        }
    }

    @SqlContractTest
    void aTakenIdOrAnOverlongColumnIsRefused(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory)) {
            WebAuthnChallengeRepository challenges = AuthRepositories.on(database).challenges();
            challenges.insert(new WebAuthnChallenge("challenge", "{}", "mod@example.com", EXPIRES));

            assertThatThrownBy(() -> challenges.insert(new WebAuthnChallenge("challenge", "[]", "other@example.com", EXPIRES)))
                .isInstanceOf(DuplicateRowException.class);
            assertThatThrownBy(() -> challenges.insert(new WebAuthnChallenge("c".repeat(256), "{}", null, EXPIRES)))
                .isInstanceOf(ValueTooLongException.class);
            assertThatThrownBy(() -> challenges.insert(new WebAuthnChallenge("email", "{}", "e".repeat(255), EXPIRES)))
                .isInstanceOf(ValueTooLongException.class);
            assertThat(challenges.consumeActive("challenge", NOW)).get()
                .extracting(WebAuthnChallenge::getEmail).isEqualTo("mod@example.com");
        }
    }
}

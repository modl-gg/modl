package gg.modl.backend.auth.repository;

import static gg.modl.backend.database.jooq.Tables.WEBAUTHN_CREDENTIALS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import gg.modl.backend.auth.data.WebAuthnCredential;
import gg.modl.backend.database.DuplicateRowException;
import gg.modl.backend.database.ValueTooLongException;
import gg.modl.backend.support.sql.DialectTarget;
import gg.modl.backend.support.sql.SqlContractTest;
import gg.modl.backend.support.sql.SqlTestDatabase;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.io.TempDir;

class WebAuthnCredentialRepositoryContractTest {
    private static final String EMAIL = "mod@example.com";
    private static final String OTHER = "other@example.com";
    private static final Instant NOW = SqlTestDatabase.START;
    private static final byte[] PUBLIC_KEY = {0, 1, -1, 127, -128, 0};

    @TempDir
    Path dataDirectory;

    @SqlContractTest
    void aCredentialRoundTrips(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory)) {
            WebAuthnCredentialRepository credentials = AuthRepositories.on(database).credentials();
            WebAuthnCredential credential = new WebAuthnCredential("id-1", EMAIL, "Abc-_xyz", PUBLIC_KEY, 4_294_967_295L, "handle",
                "🔑".repeat(255), NOW, NOW.plusSeconds(1));

            assertThat(credentials.insertIfAbsent(credential)).isTrue();

            assertThat(credentials.findByCredentialId("Abc-_xyz")).contains(credential);
            assertThat(credentials.findByEmail(" Mod@Example.com ")).containsExactly(credential);
            assertThat(credentials.findByUserHandle("handle")).contains(credential);
            assertThat(credentials.existsByEmail("MOD@example.com")).isTrue();
        }
    }

    @SqlContractTest
    void credentialIdsAndHandlesAreCaseSensitive(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory)) {
            WebAuthnCredentialRepository credentials = AuthRepositories.on(database).credentials();
            credentials.insertIfAbsent(credential("id-1", EMAIL, "AbC", 0));

            assertThat(credentials.findByCredentialId("abc")).isEmpty();
            assertThat(credentials.findByUserHandle("HANDLE-" + EMAIL)).isEmpty();
            assertThat(credentials.insertIfAbsent(credential("id-2", EMAIL, "abc", 0))).isTrue();
        }
    }

    @SqlContractTest
    void aTakenCredentialIdNeverMovesToAnotherIdentity(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory)) {
            WebAuthnCredentialRepository credentials = AuthRepositories.on(database).credentials();
            credentials.insertIfAbsent(credential("id-1", EMAIL, "shared", 3));

            assertThat(credentials.insertIfAbsent(credential("id-2", OTHER, "shared", 0))).isFalse();
            assertThatThrownBy(() -> credentials.insertIfAbsent(credential("id-1", OTHER, "different", 0)))
                .isInstanceOf(DuplicateRowException.class);

            assertThat(credentials.findByCredentialId("shared")).contains(credential("id-1", EMAIL, "shared", 3));
            assertThat(credentials.existsByEmail(OTHER)).isFalse();
        }
    }

    @SqlContractTest
    void credentialsOfAnEmailComeBackInAStableOrder(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory)) {
            WebAuthnCredentialRepository credentials = AuthRepositories.on(database).credentials();
            credentials.insertIfAbsent(credential("b", EMAIL, "credential-b", 0));
            credentials.insertIfAbsent(credential("a", EMAIL, "credential-a", 0));
            credentials.insertIfAbsent(credential("theirs", OTHER, "credential-c", 0));

            assertThat(credentials.findByEmail(EMAIL)).extracting(WebAuthnCredential::getId).containsExactly("a", "b");
            assertThat(credentials.findByUserHandle("handle-" + EMAIL)).get().extracting(WebAuthnCredential::getId).isEqualTo("a");
            assertThat(credentials.findByUserHandle(null)).isEmpty();
        }
    }

    @SqlContractTest
    void usageOnlyMovesTheCounterForward(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory)) {
            WebAuthnCredentialRepository credentials = AuthRepositories.on(database).credentials();
            credentials.insertIfAbsent(credential("id-1", EMAIL, "counted", 5));
            Instant usedAt = NOW.plusSeconds(30);

            assertThat(credentials.updateUsage("counted", 6, usedAt)).isTrue();
            assertThat(credentials.updateUsage("counted", 6, usedAt.plusSeconds(1))).isFalse();
            assertThat(credentials.updateUsage("counted", 4, usedAt.plusSeconds(1))).isFalse();
            assertThat(credentials.updateUsage("counted", 0, usedAt.plusSeconds(1))).isFalse();
            assertThat(credentials.updateUsage("unknown", 9, usedAt)).isFalse();

            WebAuthnCredential stored = credentials.findByCredentialId("counted").orElseThrow();
            assertThat(stored.getSignatureCount()).isEqualTo(6);
            assertThat(stored.getLastUsedAt()).isEqualTo(usedAt);
        }
    }

    @SqlContractTest
    void aCounterlessAuthenticatorCanBeUsedRepeatedly(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory)) {
            WebAuthnCredentialRepository credentials = AuthRepositories.on(database).credentials();
            credentials.insertIfAbsent(credential("id-1", EMAIL, "synced", 0));

            assertThat(credentials.updateUsage("synced", 0, NOW.plusSeconds(1))).isTrue();
            assertThat(credentials.updateUsage("synced", 0, NOW.plusSeconds(1))).isTrue();
        }
    }

    @SqlContractTest
    void aClonedCounterIsAcceptedOnceUnderConcurrency(DialectTarget target) throws Exception {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory)) {
            WebAuthnCredentialRepository credentials = AuthRepositories.on(database).credentials();
            credentials.insertIfAbsent(credential("id-1", EMAIL, "cloned", 5));

            List<Boolean> accepted = SimultaneousCalls.run(SimultaneousCalls.CALLERS,
                caller -> credentials.updateUsage("cloned", 6, NOW));

            assertThat(accepted).containsOnlyOnce(true);
        }
    }

    @SqlContractTest
    void renameAndDeleteNeedTheOwningEmail(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory)) {
            WebAuthnCredentialRepository credentials = AuthRepositories.on(database).credentials();
            credentials.insertIfAbsent(credential("id-1", EMAIL, "mine", 0));

            assertThat(credentials.renameByIdAndEmail("id-1", OTHER, "Stolen")).isFalse();
            assertThat(credentials.deleteByIdAndEmail("id-1", OTHER)).isFalse();
            assertThat(credentials.renameByIdAndEmail("i".repeat(37), EMAIL, "Overlong id")).isFalse();
            assertThat(credentials.deleteByIdAndEmail("i".repeat(37), EMAIL)).isFalse();

            assertThat(credentials.renameByIdAndEmail("id-1", "MOD@example.com", "  Laptop ")).isTrue();
            assertThat(credentials.renameByIdAndEmail("id-1", EMAIL, "Laptop")).isTrue();
            assertThat(credentials.findByCredentialId("mine")).get().extracting(WebAuthnCredential::getName).isEqualTo("Laptop");

            assertThat(credentials.deleteByIdAndEmail("id-1", EMAIL)).isTrue();
            assertThat(database.dsl().fetchCount(WEBAUTHN_CREDENTIALS)).isZero();
        }
    }

    @SqlContractTest
    void deletingByEmailTakesEveryCredentialOfThatEmailOnly(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory)) {
            WebAuthnCredentialRepository credentials = AuthRepositories.on(database).credentials();
            credentials.insertIfAbsent(credential("id-1", EMAIL, "first", 0));
            credentials.insertIfAbsent(credential("id-2", EMAIL, "second", 0));
            credentials.insertIfAbsent(credential("id-3", OTHER, "theirs", 0));

            assertThat(credentials.deleteAllByEmail("MOD@example.com")).isEqualTo(2);
            assertThat(credentials.existsByEmail(EMAIL)).isFalse();
            assertThat(credentials.existsByEmail(OTHER)).isTrue();
        }
    }

    @SqlContractTest
    void overlongColumnsAreRefused(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory)) {
            WebAuthnCredentialRepository credentials = AuthRepositories.on(database).credentials();
            WebAuthnCredential longName = credential("id-1", EMAIL, "name", 0);
            longName.setName("n".repeat(256));
            WebAuthnCredential longHandle = credential("id-1", EMAIL, "handle", 0);
            longHandle.setUserHandle("h".repeat(256));

            assertThatThrownBy(() -> credentials.insertIfAbsent(longName)).isInstanceOf(ValueTooLongException.class);
            assertThatThrownBy(() -> credentials.insertIfAbsent(longHandle)).isInstanceOf(ValueTooLongException.class);
            assertThatThrownBy(() -> credentials.insertIfAbsent(credential("i".repeat(37), EMAIL, "id", 0)))
                .isInstanceOf(ValueTooLongException.class);
            assertThatThrownBy(() -> credentials.insertIfAbsent(credential("id-1", EMAIL, "c".repeat(1401), 0)))
                .isInstanceOf(ValueTooLongException.class);
            assertThat(credentials.insertIfAbsent(credential("id-1", EMAIL, "c".repeat(1400), 0))).isTrue();
        }
    }

    private static WebAuthnCredential credential(String id, String email, String credentialId, long signatureCount) {
        return new WebAuthnCredential(id, email, credentialId, PUBLIC_KEY, signatureCount, "handle-" + email, "Passkey", NOW, NOW);
    }
}

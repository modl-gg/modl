package gg.modl.backend.auth;

import static gg.modl.backend.database.jooq.Tables.AUTH_CODES;
import static gg.modl.backend.database.jooq.Tables.MINECRAFT_LINK_CODES;
import static gg.modl.backend.database.jooq.Tables.PANEL_LOGIN_TOKENS;
import static gg.modl.backend.database.jooq.Tables.PANEL_SESSIONS;
import static gg.modl.backend.database.jooq.Tables.PASSWORD_RESET_TOKENS;
import static gg.modl.backend.database.jooq.Tables.STAFF_CREDENTIALS;
import static gg.modl.backend.database.jooq.Tables.STAFF_RECOVERY_CODES;
import static gg.modl.backend.database.jooq.Tables.STAFF_TOTP;
import static gg.modl.backend.database.jooq.Tables.WEBAUTHN_CHALLENGES;
import static gg.modl.backend.database.jooq.Tables.WEBAUTHN_CREDENTIALS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

import gg.modl.backend.auth.data.WebAuthnChallenge;
import gg.modl.backend.auth.data.WebAuthnCredential;
import gg.modl.backend.auth.link.MinecraftLinkCode;
import gg.modl.backend.auth.link.PanelLoginToken;
import gg.modl.backend.auth.repository.AuthRepositories;
import gg.modl.backend.auth.repository.MinecraftLinkCodeRepository;
import gg.modl.backend.auth.repository.PanelSession;
import gg.modl.backend.auth.session.AuthMethod;
import gg.modl.backend.server.data.Server;
import gg.modl.backend.support.TestServers;
import gg.modl.backend.support.sql.DialectTarget;
import gg.modl.backend.support.sql.RetryInjection;
import gg.modl.backend.support.sql.SqlContractTest;
import gg.modl.backend.support.sql.SqlTestDatabase;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import org.jooq.Table;
import org.junit.jupiter.api.io.TempDir;

class AccountOffboardingTest {
    private static final String LEAVING = "mod@example.com";
    private static final String STAYING = "stays@example.com";
    private static final Instant NOW = SqlTestDatabase.START;
    private static final Instant LATER = NOW.plusSeconds(600);
    private static final List<Table<?>> ACCESS_TABLES =
        List.of(PANEL_SESSIONS, WEBAUTHN_CREDENTIALS, WEBAUTHN_CHALLENGES, AUTH_CODES, PANEL_LOGIN_TOKENS, MINECRAFT_LINK_CODES,
            STAFF_CREDENTIALS, STAFF_TOTP, STAFF_RECOVERY_CODES, PASSWORD_RESET_TOKENS);

    private final Server server = TestServers.server("Server", "owner@example.com");

    @TempDir
    Path dataDirectory;

    @SqlContractTest
    void revokingAccessPurgesEveryKindOfAccessOfThatAccountOnly(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory)) {
            AuthRepositories repositories = AuthRepositories.on(database);
            seed(repositories, LEAVING);
            seed(repositories, STAYING);

            offboarding(database, repositories, repositories.linkCodes()).revokeAllAccess(server, " Mod@Example.com ");

            ACCESS_TABLES.forEach(table -> assertThat(database.dsl().fetchCount(table)).isEqualTo(1));
            assertThat(repositories.sessions().findByEmail(STAYING, NOW)).hasSize(1);
            assertThat(repositories.credentials().existsByEmail(STAYING)).isTrue();
            assertThat(repositories.challenges().consumeActive("challenge-stays", NOW)).isPresent();
            assertThat(repositories.authCodes().find(STAYING)).isPresent();
            assertThat(repositories.loginTokens().consumeActive("token-" + STAYING, NOW)).isPresent();
            assertThat(repositories.linkCodes().findActive("link-" + STAYING, NOW)).isPresent();
        }
    }

    @SqlContractTest
    void revokingAccessIsOneRetryableUnit(DialectTarget target) {
        RetryInjection retries = new RetryInjection();
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory, retries)) {
            AuthRepositories repositories = AuthRepositories.on(database);
            seed(repositories, LEAVING);
            int unitsBefore = retries.injected();

            offboarding(database, repositories, repositories.linkCodes()).revokeAllAccess(server, LEAVING);

            assertThat(retries.injected() - unitsBefore).isEqualTo(1);
            ACCESS_TABLES.forEach(table -> assertThat(database.dsl().fetchCount(table)).isZero());
        }
    }

    @SqlContractTest
    void aFailureLeavesEveryKindOfAccessInPlace(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory)) {
            AuthRepositories repositories = AuthRepositories.on(database);
            seed(repositories, LEAVING);
            MinecraftLinkCodeRepository failing = mock(MinecraftLinkCodeRepository.class);
            doThrow(new IllegalStateException("disk failed")).when(failing).deleteForIdentity(LEAVING);

            assertThatThrownBy(() -> offboarding(database, repositories, failing).revokeAllAccess(server, LEAVING))
                .hasMessageContaining("disk failed");

            ACCESS_TABLES.forEach(table -> assertThat(database.dsl().fetchCount(table)).isEqualTo(1));
        }
    }

    @SqlContractTest
    void transferringAnIdentityMovesThePasswordAndAuthenticatorAndRevokesTheRest(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory)) {
            AuthRepositories repositories = AuthRepositories.on(database);
            seed(repositories, LEAVING);
            repositories.passwords().replace("moved@example.com", "orphan-hash", NOW);

            offboarding(database, repositories, repositories.linkCodes()).transferIdentity(server, LEAVING, "Moved@Example.com");

            assertThat(repositories.passwords().findPasswordHash("moved@example.com")).contains("hash-mod");
            assertThat(repositories.passwords().findPasswordHash(LEAVING)).isEmpty();
            assertThat(repositories.totp().find("moved@example.com")).isPresent();
            assertThat(repositories.totp().countRecoveryCodes("moved@example.com")).isEqualTo(1);
            assertThat(repositories.sessions().findByEmail(LEAVING, NOW)).isEmpty();
            assertThat(repositories.credentials().existsByEmail(LEAVING)).isFalse();
            assertThat(database.dsl().fetchCount(PASSWORD_RESET_TOKENS)).isZero();
        }
    }

    @SqlContractTest
    void endingSessionsLeavesPasskeysAndCodesAlone(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory)) {
            AuthRepositories repositories = AuthRepositories.on(database);
            seed(repositories, LEAVING);

            offboarding(database, repositories, repositories.linkCodes()).endSessions(server, "Mod@Example.com");

            ACCESS_TABLES.forEach(table ->
                assertThat(database.dsl().fetchCount(table)).isEqualTo(table.equals(PANEL_SESSIONS) ? 0 : 1));
        }
    }

    @SqlContractTest
    void aBlankIdentityTouchesNothing(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory)) {
            AuthRepositories repositories = AuthRepositories.on(database);
            seed(repositories, LEAVING);
            AccountOffboarding offboarding = offboarding(database, repositories, repositories.linkCodes());

            offboarding.revokeAllAccess(server, " ");
            offboarding.endSessions(server, null);

            ACCESS_TABLES.forEach(table -> assertThat(database.dsl().fetchCount(table)).isEqualTo(1));
        }
    }

    private static AccountOffboarding offboarding(SqlTestDatabase database, AuthRepositories repositories,
                                                  MinecraftLinkCodeRepository linkCodes) {
        return new AccountOffboarding(repositories.sessions(), repositories.credentials(), repositories.challenges(),
            repositories.authCodes(), repositories.loginTokens(), linkCodes, repositories.passwords(), repositories.totp(),
            repositories.resetTokens(), database.unitOfWork(), database.clock());
    }

    private static void seed(AuthRepositories repositories, String email) {
        String key = email.substring(0, email.indexOf('@'));
        repositories.sessions().insert(new PanelSession("session-" + key, email, "digest", null, null, NOW, LATER, AuthMethod.PASSWORD,
            true));
        repositories.passwords().replace(email, "hash-" + key, NOW);
        repositories.totp().savePending(email, "PENDING" + key.toUpperCase(Locale.ROOT), LATER, NOW);
        repositories.totp().replaceRecoveryCodes(email, List.of("recovery-" + key), NOW);
        repositories.resetTokens().replace(email, "reset-" + key, NOW, LATER);
        repositories.credentials().insertIfAbsent(
            new WebAuthnCredential(key, email, "credential-" + key, new byte[] {1, 2}, 0, "handle-" + key, "Passkey", NOW, NOW));
        repositories.challenges().insert(new WebAuthnChallenge("challenge-" + key, "{}", email, LATER));
        repositories.authCodes().issue(email, "code-" + key, NOW, LATER, NOW);
        repositories.loginTokens().insert(PanelLoginToken.builder()
            .id(key).tokenHash("token-" + email).identityEmail(email).expiresAt(LATER).createdAt(NOW).build());
        repositories.linkCodes().replaceForIdentity(MinecraftLinkCode.builder()
            .id(key).codeHash("link-" + email).identityEmail(email).expiresAt(LATER).createdAt(NOW).build());
    }
}

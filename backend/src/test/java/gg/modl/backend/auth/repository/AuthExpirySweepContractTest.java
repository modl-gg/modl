package gg.modl.backend.auth.repository;

import static gg.modl.backend.database.jooq.Tables.AUTH_CODES;
import static gg.modl.backend.database.jooq.Tables.MINECRAFT_LINK_CODES;
import static gg.modl.backend.database.jooq.Tables.PANEL_LOGIN_TOKENS;
import static gg.modl.backend.database.jooq.Tables.PANEL_SESSIONS;
import static gg.modl.backend.database.jooq.Tables.WEBAUTHN_CHALLENGES;
import static org.assertj.core.api.Assertions.assertThat;

import gg.modl.backend.auth.data.WebAuthnChallenge;
import gg.modl.backend.auth.link.MinecraftLinkCode;
import gg.modl.backend.auth.link.PanelLoginToken;
import gg.modl.backend.auth.session.AuthMethod;
import gg.modl.backend.database.ExpiredRowSweeper;
import gg.modl.backend.support.sql.DialectTarget;
import gg.modl.backend.support.sql.SqlContractTest;
import gg.modl.backend.support.sql.SqlTestDatabase;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.jooq.Table;
import org.junit.jupiter.api.io.TempDir;

class AuthExpirySweepContractTest {
    private static final Instant NOW = SqlTestDatabase.START;
    private static final List<Table<?>> EXPIRING_AUTH_TABLES =
        List.of(AUTH_CODES, PANEL_SESSIONS, WEBAUTHN_CHALLENGES, PANEL_LOGIN_TOKENS, MINECRAFT_LINK_CODES);

    @TempDir
    Path dataDirectory;

    @SqlContractTest
    void theSweeperDeletesExpiredAuthRowsAndKeepsLiveOnes(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory)) {
            AuthRepositories repositories = AuthRepositories.on(database);
            seed(repositories, "expired", NOW.minusSeconds(1));
            seed(repositories, "live", NOW.plusSeconds(1));

            assertThat(database.bean(ExpiredRowSweeper.class).sweep()).isEqualTo(EXPIRING_AUTH_TABLES.size());

            EXPIRING_AUTH_TABLES.forEach(table -> assertThat(database.dsl().fetchCount(table)).isEqualTo(1));
            assertThat(repositories.authCodes().find("live@example.com")).isPresent();
            assertThat(repositories.sessions().findByPublicId("live")).isPresent();
            assertThat(repositories.challenges().consumeActive("live", NOW)).isPresent();
            assertThat(repositories.loginTokens().consumeActive("live", NOW)).isPresent();
            assertThat(repositories.linkCodes().consumeActive("live", NOW)).isPresent();
        }
    }

    private static void seed(AuthRepositories repositories, String key, Instant expiresAt) {
        String email = key + "@example.com";
        repositories.authCodes().issue(email, key, NOW, expiresAt, NOW);
        repositories.sessions().insert(new PanelSession(key, email, "digest", null, null, NOW, expiresAt, AuthMethod.EMAIL_CODE, false));
        repositories.challenges().insert(new WebAuthnChallenge(key, "{}", email, expiresAt));
        repositories.loginTokens().insert(PanelLoginToken.builder()
            .id(key).tokenHash(key).identityEmail(email).expiresAt(expiresAt).createdAt(NOW).build());
        repositories.linkCodes().replaceForIdentity(MinecraftLinkCode.builder()
            .id(key).codeHash(key).identityEmail(email).expiresAt(expiresAt).createdAt(NOW).build());
    }
}

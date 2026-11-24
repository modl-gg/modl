package gg.modl.backend.auth.repository;

import static gg.modl.backend.database.jooq.Tables.PANEL_LOGIN_TOKENS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import gg.modl.backend.auth.link.PanelLoginToken;
import gg.modl.backend.database.DuplicateRowException;
import gg.modl.backend.database.ValueTooLongException;
import gg.modl.backend.support.sql.DialectTarget;
import gg.modl.backend.support.sql.SqlContractTest;
import gg.modl.backend.support.sql.SqlTestDatabase;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.io.TempDir;

class PanelLoginTokenRepositoryContractTest {
    private static final String EMAIL = "mod@example.com";
    private static final String OTHER = "other@example.com";
    private static final String UUID = "0f8fad5b-d9cb-469f-a165-70867728950e";
    private static final Instant NOW = SqlTestDatabase.START;
    private static final Instant EXPIRES = NOW.plusSeconds(120);

    @TempDir
    Path dataDirectory;

    @SqlContractTest
    void aTokenRoundTripsWithNormalizedIdentityAndUuid(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory)) {
            PanelLoginTokenRepository tokens = AuthRepositories.on(database).loginTokens();
            tokens.insert(token("id-1", "Hash", "Mod@Example.com", UUID.toUpperCase(Locale.ROOT)));

            PanelLoginToken consumed = tokens.consumeActive("Hash", NOW).orElseThrow();

            assertThat(consumed.getId()).isEqualTo("id-1");
            assertThat(consumed.getTokenHash()).isEqualTo("Hash");
            assertThat(consumed.getIdentityEmail()).isEqualTo(EMAIL);
            assertThat(consumed.getMinecraftUuid()).isEqualTo(UUID);
            assertThat(consumed.getExpiresAt()).isEqualTo(EXPIRES);
            assertThat(consumed.getCreatedAt()).isEqualTo(NOW);
        }
    }

    @SqlContractTest
    void aTokenIsConsumedOnceUnderConcurrency(DialectTarget target) throws Exception {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory)) {
            PanelLoginTokenRepository tokens = AuthRepositories.on(database).loginTokens();
            tokens.insert(token("id-1", "once", EMAIL, UUID));

            List<Boolean> consumed = SimultaneousCalls.run(SimultaneousCalls.CALLERS,
                caller -> tokens.consumeActive("once", NOW).isPresent());

            assertThat(consumed).containsOnlyOnce(true);
            assertThat(database.dsl().fetchCount(PANEL_LOGIN_TOKENS)).isZero();
        }
    }

    @SqlContractTest
    void anExpiredOrDifferentHashIsNotConsumed(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory)) {
            PanelLoginTokenRepository tokens = AuthRepositories.on(database).loginTokens();
            tokens.insert(token("id-1", "Hash", EMAIL, UUID));

            assertThat(tokens.consumeActive("Hash", EXPIRES)).isEmpty();
            assertThat(tokens.consumeActive("hash", NOW)).isEmpty();
            assertThat(tokens.consumeActive("h".repeat(256), NOW)).isEmpty();
            assertThat(database.dsl().fetchCount(PANEL_LOGIN_TOKENS)).isEqualTo(1);
        }
    }

    @SqlContractTest
    void deletingForAnIdentityTakesEveryTokenOfThatIdentityOnly(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory)) {
            PanelLoginTokenRepository tokens = AuthRepositories.on(database).loginTokens();
            tokens.insert(token("id-1", "first", EMAIL, UUID));
            tokens.insert(token("id-2", "second", EMAIL, UUID));
            tokens.insert(token("id-3", "theirs", OTHER, null));

            tokens.deleteForIdentity("MOD@example.com");

            assertThat(database.dsl().fetch(PANEL_LOGIN_TOKENS).getValues(PANEL_LOGIN_TOKENS.ID)).containsExactly("id-3");
        }
    }

    @SqlContractTest
    void aTakenHashOrAnOverlongColumnIsRefused(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory)) {
            PanelLoginTokenRepository tokens = AuthRepositories.on(database).loginTokens();
            tokens.insert(token("id-1", "taken", EMAIL, UUID));

            assertThatThrownBy(() -> tokens.insert(token("id-2", "taken", OTHER, UUID))).isInstanceOf(DuplicateRowException.class);
            assertThatThrownBy(() -> tokens.insert(token("i".repeat(37), "a", EMAIL, UUID))).isInstanceOf(ValueTooLongException.class);
            assertThatThrownBy(() -> tokens.insert(token("id-3", "h".repeat(256), EMAIL, UUID)))
                .isInstanceOf(ValueTooLongException.class);
            assertThatThrownBy(() -> tokens.insert(token("id-4", "b", "e".repeat(255), UUID))).isInstanceOf(ValueTooLongException.class);
            assertThatThrownBy(() -> tokens.insert(token("id-5", "c", EMAIL, "u".repeat(37)))).isInstanceOf(ValueTooLongException.class);
            assertThat(tokens.consumeActive("taken", NOW)).get().extracting(PanelLoginToken::getIdentityEmail).isEqualTo(EMAIL);
        }
    }

    private static PanelLoginToken token(String id, String hash, String email, String uuid) {
        return PanelLoginToken.builder()
            .id(id)
            .tokenHash(hash)
            .identityEmail(email)
            .minecraftUuid(uuid)
            .expiresAt(EXPIRES)
            .createdAt(NOW)
            .build();
    }
}

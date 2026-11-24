package gg.modl.backend.auth.session;

import static gg.modl.backend.database.jooq.Tables.PANEL_SESSIONS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import gg.modl.backend.auth.AuthConfiguration;
import gg.modl.backend.auth.repository.AuthRepositories;
import gg.modl.backend.auth.settings.SignInSettings;
import gg.modl.backend.auth.settings.SignInSettingsService;
import gg.modl.backend.auth.signin.SecondFactorPolicy;
import gg.modl.backend.auth.repository.PanelSession;
import gg.modl.backend.database.jooq.tables.records.PanelSessionsRecord;
import gg.modl.backend.infrastructure.util.IdGenerator;
import gg.modl.backend.server.data.Server;
import gg.modl.backend.support.TestServers;
import gg.modl.backend.support.sql.DialectTarget;
import gg.modl.backend.support.sql.SqlContractTest;
import gg.modl.backend.support.sql.SqlTestDatabase;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Set;
import org.junit.jupiter.api.io.TempDir;

class SessionServiceContractTest {
    private static final String EMAIL = "mod@example.com";

    private final Server server = TestServers.server("Server", "owner@example.com");
    private final AuthConfiguration configuration = new AuthConfiguration();

    @TempDir
    Path dataDirectory;

    @SqlContractTest
    void neitherTheSessionIdNorTheKeyIsStored(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory)) {
            IssuedSession issued = service(database).createSession(server, "Mod@Example.com", AuthMethod.EMAIL_CODE, false, "127.0.0.1", "JUnit");
            String sessionId = issued.session().getId();

            PanelSessionsRecord row = database.dsl().fetchSingle(PANEL_SESSIONS);

            assertThat(row.getIdHash()).isEqualTo(SessionPublicId.of(sessionId));
            assertThat(row.getSessionKeyHash()).isEqualTo(SessionKeyDigest.digest(issued.sessionKey()));
            assertThat(row.intoList().toString()).doesNotContain(sessionId, issued.sessionKey());
        }
    }

    @SqlContractTest
    void theStoredPublicIdListsAuthenticatesAndRevokes(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory)) {
            SessionService service = service(database);
            IssuedSession issued = service.createSession(server, EMAIL, AuthMethod.EMAIL_CODE, false, null, null);
            String sessionId = issued.session().getId();
            String publicId = SessionPublicId.of(sessionId);

            assertThat(service.findAllSessionsForEmail(server, EMAIL)).extracting(PanelSession::publicId).containsExactly(publicId);
            assertThat(service.authenticate(server, publicId, issued.sessionKey())).isEmpty();
            assertThat(service.authenticate(server, sessionId, "wrong-key")).isEmpty();
            assertThat(service.authenticate(server, sessionId, issued.sessionKey())).get()
                .extracting(AuthSessionData::getId).isEqualTo(sessionId);
            assertThat(service.revokeOwnSession(server, "other@example.com", publicId)).isFalse();
            assertThat(service.activeSessionIds(server, Set.of(sessionId, "unknown"))).containsExactly(sessionId);

            assertThat(service.revokeOwnSession(server, EMAIL, publicId)).isTrue();

            assertThat(service.authenticate(server, sessionId, issued.sessionKey())).isEmpty();
            assertThat(service.activeSessionIds(server, Set.of(sessionId))).isEmpty();
        }
    }

    @SqlContractTest
    void useExtendsASessionThatExpiresOnceIdle(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory)) {
            SessionService service = service(database);
            Duration lifetime = Duration.ofSeconds(configuration.getSessionDurationSeconds());
            IssuedSession issued = service.createSession(server, EMAIL, AuthMethod.EMAIL_CODE, false, null, null);
            String sessionId = issued.session().getId();

            database.clock().advance(lifetime.minusMinutes(1));
            assertThat(service.authenticate(server, sessionId, issued.sessionKey())).isPresent();
            assertThat(database.dsl().fetchSingle(PANEL_SESSIONS).getExpiresAt())
                .isEqualTo(database.clock().instant().plus(lifetime));

            database.clock().advance(lifetime);
            assertThat(service.authenticate(server, sessionId, issued.sessionKey())).isEmpty();
            assertThat(service.findAllSessionsForEmail(server, EMAIL)).isEmpty();
        }
    }

    private SessionService service(SqlTestDatabase database) {
        SecondFactorPolicy admitAll = mock(SecondFactorPolicy.class);
        when(admitAll.admitsEverywhere(any())).thenReturn(true);
        SignInSettingsService settings = mock(SignInSettingsService.class);
        when(settings.current()).thenReturn(SignInSettings.DEFAULTS);
        return new SessionService(AuthRepositories.on(database).sessions(), admitAll, settings, configuration, new IdGenerator(),
            database.clock());
    }
}

package gg.modl.backend.auth;

import static gg.modl.backend.database.jooq.Tables.PANEL_SESSIONS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import gg.modl.backend.auth.repository.AuthRepositories;
import gg.modl.backend.auth.session.AuthMethod;
import gg.modl.backend.auth.session.IssuedSession;
import gg.modl.backend.auth.session.SessionService;
import gg.modl.backend.auth.settings.SignInSettings;
import gg.modl.backend.auth.settings.SignInSettingsService;
import gg.modl.backend.auth.signin.CredentialProof;
import gg.modl.backend.auth.signin.EmailChangeProof;
import gg.modl.backend.auth.signin.Reauthentication;
import gg.modl.backend.auth.signin.SecondFactorPolicy;
import gg.modl.backend.auth.signin.SignInClient;
import gg.modl.backend.email.EmailService;
import gg.modl.backend.infrastructure.exception.ForbiddenException;
import gg.modl.backend.infrastructure.util.IdGenerator;
import gg.modl.backend.instance.InstanceProperties;
import gg.modl.backend.instance.InstanceService;
import gg.modl.backend.role.service.PermissionService;
import gg.modl.backend.server.data.Server;
import gg.modl.backend.staff.service.StaffProfileService;
import gg.modl.backend.support.TestServers;
import gg.modl.backend.support.sql.DialectTarget;
import gg.modl.backend.support.sql.RetryInjection;
import gg.modl.backend.support.sql.SqlContractTest;
import gg.modl.backend.support.sql.SqlTestDatabase;
import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.io.TempDir;

class EmailChangeServiceContractTest {
    private static final String CURRENT = "owner@example.com";
    private static final String NEXT = "next@example.com";
    private static final String CODE = "123456";

    private final Server server = TestServers.server("Server", CURRENT);
    private final PermissionService permissionService = mock(PermissionService.class);
    private final AuthService authService = mock(AuthService.class);
    private final StaffProfileService staffProfileService = mock(StaffProfileService.class);
    private final InstanceService instanceService = mock(InstanceService.class);
    private final EmailService emailService = mock(EmailService.class);

    @TempDir
    Path dataDirectory;

    @SqlContractTest
    void theOwnerMovesWithExactlyOneNewSessionEvenWhenTheUnitRetries(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory, new RetryInjection())) {
            SessionService sessions = sessions(database);
            IssuedSession current = sessions.createSession(server, CURRENT, AuthMethod.PASSWORD, true, null, null);
            givenAVerifiedOwner();

            IssuedSession issued = service(database, sessions).changeEmail(server, current.session(), NEXT, CODE, CredentialProof.NONE, SignInClient.unidentified());

            assertThat(sessions.findAllSessionsForEmail(server, CURRENT)).isEmpty();
            assertThat(sessions.findAllSessionsForEmail(server, NEXT)).hasSize(1);
            assertThat(sessions.authenticate(server, issued.session().getId(), issued.sessionKey())).isPresent();
        }
    }

    @SqlContractTest
    void thePasswordMovesWithTheAccountAndTheVerifiedFlagNeedsAnAuthenticator(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory)) {
            SessionService sessions = sessions(database);
            AuthRepositories repositories = AuthRepositories.on(database);
            repositories.passwords().replace(CURRENT, "owner-hash", database.clock().instant());
            IssuedSession current = sessions.createSession(server, CURRENT, AuthMethod.PASSWORD, true, null, null);
            givenAVerifiedOwner();

            IssuedSession issued = service(database, sessions).changeEmail(server, current.session(), NEXT, CODE, CredentialProof.NONE,
                SignInClient.unidentified());

            assertThat(repositories.passwords().findPasswordHash(NEXT)).contains("owner-hash");
            assertThat(repositories.passwords().findPasswordHash(CURRENT)).isEmpty();
            assertThat(issued.session().getAuthMethod()).isEqualTo(AuthMethod.PASSWORD);
            assertThat(issued.session().isSecondFactorVerified()).isFalse();
        }
    }

    @SqlContractTest
    void aFailedOwnerUpdateKeepsTheOldSessionsAndCreatesNone(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory)) {
            SessionService sessions = sessions(database);
            IssuedSession current = sessions.createSession(server, CURRENT, AuthMethod.PASSWORD, true, null, null);
            givenAVerifiedOwner();
            when(instanceService.changeAdminEmail(NEXT)).thenThrow(new IllegalStateException("instance update failed"));

            assertThatThrownBy(() -> service(database, sessions).changeEmail(server, current.session(), NEXT, CODE, CredentialProof.NONE, SignInClient.unidentified()))
                .hasMessageContaining("instance update failed");

            assertThat(sessions.findAllSessionsForEmail(server, CURRENT)).hasSize(1);
            assertThat(sessions.findAllSessionsForEmail(server, NEXT)).isEmpty();
            assertThat(database.dsl().fetchCount(PANEL_SESSIONS)).isEqualTo(1);
            verify(authService).verifyCode(server, NEXT, CODE);
        }
    }

    @SqlContractTest
    void aWrongCodeChangesNothing(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory)) {
            SessionService sessions = sessions(database);
            IssuedSession current = sessions.createSession(server, CURRENT, AuthMethod.PASSWORD, true, null, null);
            when(permissionService.isSuperAdmin(server, CURRENT)).thenReturn(true);

            assertThatThrownBy(() -> service(database, sessions).changeEmail(server, current.session(), NEXT, "000000", CredentialProof.NONE, SignInClient.unidentified()))
                .hasMessage("Invalid or expired verification code.");

            assertThat(sessions.findAllSessionsForEmail(server, CURRENT)).hasSize(1);
            verify(instanceService, never()).changeAdminEmail(NEXT);
        }
    }

    @SqlContractTest
    void aCodeGoesToTheCurrentAddressOnlyWhenItIsTheProofTheAccountNeeds(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory)) {
            Reauthentication reauthentication = mock(Reauthentication.class);
            EmailChangeService service = service(database, sessions(database), reauthentication);

            when(reauthentication.emailChangeProof(CURRENT)).thenReturn(EmailChangeProof.CURRENT_ADDRESS_CODE);
            service.sendChangeCode(server, CURRENT, NEXT);
            when(reauthentication.emailChangeProof(CURRENT)).thenReturn(EmailChangeProof.PASSWORD);
            service.sendChangeCode(server, CURRENT, NEXT);

            verify(authService, times(2)).sendEmailChangeCode(server, NEXT);
            verify(authService, times(1)).sendEmailChangeApprovalCode(server, CURRENT);
        }
    }

    @SqlContractTest
    void aStaleSessionCannotMoveTheAccountWithoutTheEmailChangeProof(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory)) {
            SessionService sessions = sessions(database);
            IssuedSession current = sessions.createSession(server, CURRENT, AuthMethod.EMAIL_CODE, true, null, null);
            givenAVerifiedOwner();
            Reauthentication reauthentication = mock(Reauthentication.class);
            doThrow(AuthFailures.reauthenticationRequired()).when(reauthentication)
                .requireForEmailChange(server, current.session(), CredentialProof.NONE, SignInClient.unidentified());

            assertThatThrownBy(() -> service(database, sessions, reauthentication).changeEmail(server, current.session(), NEXT, CODE,
                CredentialProof.NONE, SignInClient.unidentified())).isInstanceOf(ForbiddenException.class);

            assertThat(sessions.findAllSessionsForEmail(server, CURRENT)).hasSize(1);
            verify(authService, never()).verifyCode(server, NEXT, CODE);
            verify(instanceService, never()).changeAdminEmail(NEXT);
        }
    }

    private void givenAVerifiedOwner() {
        when(permissionService.isSuperAdmin(server, CURRENT)).thenReturn(true);
        when(authService.verifyCode(server, NEXT, CODE)).thenReturn(true);
        when(staffProfileService.applyStaffEmailChange(server, CURRENT, NEXT)).thenReturn(Optional.empty());
    }

    private EmailChangeService service(SqlTestDatabase database, SessionService sessions) {
        return service(database, sessions, mock(Reauthentication.class));
    }

    private EmailChangeService service(SqlTestDatabase database, SessionService sessions, Reauthentication reauthentication) {
        AuthRepositories repositories = AuthRepositories.on(database);
        AccountOffboarding offboarding = new AccountOffboarding(repositories.sessions(), repositories.credentials(),
            repositories.challenges(), repositories.authCodes(), repositories.loginTokens(), repositories.linkCodes(),
            repositories.passwords(), repositories.totp(), repositories.resetTokens(), database.unitOfWork(), database.clock());
        return new EmailChangeService(permissionService, authService, staffProfileService, instanceService,
            new InstanceProperties(null, null, null, null, null, null), offboarding, sessions, emailService, repositories.totp(),
            reauthentication, database.unitOfWork());
    }

    private static SessionService sessions(SqlTestDatabase database) {
        SecondFactorPolicy admitAll = mock(SecondFactorPolicy.class);
        when(admitAll.admitsEverywhere(any())).thenReturn(true);
        SignInSettingsService settings = mock(SignInSettingsService.class);
        when(settings.current()).thenReturn(SignInSettings.DEFAULTS);
        return new SessionService(AuthRepositories.on(database).sessions(), admitAll, settings, new AuthConfiguration(), new IdGenerator(),
            database.clock());
    }
}

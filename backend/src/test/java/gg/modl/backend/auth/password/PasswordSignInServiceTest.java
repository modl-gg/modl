package gg.modl.backend.auth.password;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import gg.modl.backend.auth.ImmediateUnitOfWork;
import gg.modl.backend.auth.repository.StaffCredentialRepository;
import gg.modl.backend.auth.session.AuthMethod;
import gg.modl.backend.auth.settings.SignInMethod;
import gg.modl.backend.auth.settings.SignInSettings;
import gg.modl.backend.auth.settings.SignInSettingsStub;
import gg.modl.backend.auth.signin.CredentialAttempts;
import gg.modl.backend.auth.signin.SignInAttempt;
import gg.modl.backend.auth.signin.SignInClient;
import gg.modl.backend.auth.signin.SignInCompletion;
import gg.modl.backend.infrastructure.exception.BaseApplicationException;
import gg.modl.backend.infrastructure.exception.ServiceUnavailableException;
import gg.modl.backend.infrastructure.ratelimit.BucketPool;
import gg.modl.backend.role.service.PermissionService;
import gg.modl.backend.server.data.Server;
import gg.modl.backend.support.TestServers;
import java.time.Clock;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class PasswordSignInServiceTest {
    private static final Server SERVER = TestServers.server("Alpha", "owner@example.com");
    private static final String PASSWORD = "a long enough password";

    private final StaffCredentialRepository credentials = mock(StaffCredentialRepository.class);
    private final PermissionService permissions = mock(PermissionService.class);
    private final PasswordHasher hasher = mock(PasswordHasher.class);
    private final SignInCompletion completion = mock(SignInCompletion.class);

    @Test
    void everyFailureLooksTheSameAndCostsExactlyOneHash() {
        when(credentials.findPasswordHash("known@example.com")).thenReturn(Optional.of("known-hash"));
        when(credentials.findPasswordHash("unauthorized@example.com")).thenReturn(Optional.of("old-hash"));
        when(permissions.isAuthorizedEmail(SERVER, "known@example.com")).thenReturn(true);
        when(permissions.isAuthorizedEmail(SERVER, "passwordless@example.com")).thenReturn(true);
        when(hasher.matches(PASSWORD, "old-hash")).thenReturn(true);

        String wrong = failureOf("known@example.com");
        assertThat(failureOf("unknown@example.com")).isEqualTo(wrong);
        assertThat(failureOf("unauthorized@example.com")).isEqualTo(wrong);
        assertThat(failureOf("passwordless@example.com")).isEqualTo(wrong);

        verify(hasher, times(2)).matches(anyString(), anyString());
        verify(hasher, times(2)).burnForUnknownAccount(PASSWORD);
        verify(completion, never()).complete(any(), any(), any());
    }

    @Test
    void aMatchingPasswordCompletesWithTheVerifiedHashAsPrecondition() {
        when(credentials.findPasswordHash("known@example.com")).thenReturn(Optional.of("known-hash"));
        when(permissions.isAuthorizedEmail(SERVER, "known@example.com")).thenReturn(true);
        when(hasher.matches(PASSWORD, "known-hash")).thenReturn(true);

        service(SignInMethod.PASSWORD).signIn(SERVER, "Known@Example.com", PASSWORD, SignInClient.unidentified());

        verify(completion).complete(SERVER, new SignInAttempt("known@example.com", AuthMethod.PASSWORD, "known-hash"),
            SignInClient.unidentified());
    }

    @Test
    void passwordSignInIsOffInEmailCodeModeBeforeAnyHash() {
        assertThatThrownBy(() -> service(SignInMethod.EMAIL_CODE).signIn(SERVER, "known@example.com", PASSWORD, SignInClient.unidentified()))
            .isInstanceOfSatisfying(BaseApplicationException.class,
                refused -> assertThat(refused.getMachineCode()).isEqualTo("SIGN_IN_METHOD_DISABLED"));
        verify(hasher, never()).matches(anyString(), anyString());
    }

    @Test
    void aBusyGateRefundsTheAttempt() {
        PasswordSignInService service = service(SignInMethod.PASSWORD);
        when(credentials.findPasswordHash("known@example.com")).thenReturn(Optional.of("known-hash"));
        when(hasher.matches(PASSWORD, "known-hash")).thenThrow(new ServiceUnavailableException("busy"));

        for (int attempt = 0; attempt < 10; attempt++) {
            assertThatThrownBy(() -> service.signIn(SERVER, "known@example.com", PASSWORD, SignInClient.unidentified()))
                .isInstanceOf(ServiceUnavailableException.class);
        }
        reset(hasher);
        assertThatThrownBy(() -> service.signIn(SERVER, "known@example.com", PASSWORD, SignInClient.unidentified()))
            .isInstanceOfSatisfying(BaseApplicationException.class,
                refused -> assertThat(refused.getMachineCode()).isEqualTo("INVALID_CREDENTIALS"));
    }

    private String failureOf(String email) {
        PasswordSignInService service = service(SignInMethod.PASSWORD);
        Throwable failure = catchThrowable(
            () -> service.signIn(SERVER, email, PASSWORD, SignInClient.unidentified()));
        BaseApplicationException refused = (BaseApplicationException) failure;
        return refused.getStatus() + " " + refused.getMachineCode() + " " + refused.getMessage();
    }

    private PasswordSignInService service(SignInMethod method) {
        return new PasswordSignInService(SignInSettingsStub.with(new SignInSettings(method, false)),
            new PasswordVerification(new CredentialAttempts(new BucketPool()), credentials, hasher), credentials, permissions, hasher,
            completion, new ImmediateUnitOfWork(),
            Clock.systemUTC());
    }
}

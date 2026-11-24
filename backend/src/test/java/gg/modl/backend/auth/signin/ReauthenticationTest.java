package gg.modl.backend.auth.signin;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import gg.modl.backend.auth.AuthService;
import gg.modl.backend.auth.ImmediateUnitOfWork;
import gg.modl.backend.auth.password.PasswordHasher;
import gg.modl.backend.auth.password.PasswordVerification;
import gg.modl.backend.auth.repository.StaffCredentialRepository;
import gg.modl.backend.auth.session.AuthMethod;
import gg.modl.backend.auth.session.AuthSessionData;
import gg.modl.backend.auth.totp.SecondFactorMethod;
import gg.modl.backend.auth.totp.SecondFactorVerifier;
import gg.modl.backend.infrastructure.exception.BaseApplicationException;
import gg.modl.backend.infrastructure.ratelimit.BucketPool;
import gg.modl.backend.server.data.Server;
import gg.modl.backend.support.TestServers;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ReauthenticationTest {
    private static final Instant NOW = Instant.parse("2026-11-22T12:00:00Z");
    private static final String EMAIL = "mod@example.com";

    private final StaffCredentialRepository credentials = mock(StaffCredentialRepository.class);
    private final AccountFactors factors = mock(AccountFactors.class);
    private final PasswordHasher hasher = mock(PasswordHasher.class);
    private final SecondFactorVerifier verifier = mock(SecondFactorVerifier.class);
    private final AuthService authService = mock(AuthService.class);
    private final Server server = TestServers.server("Demo", "owner@example.com");
    private final CredentialAttempts attempts = new CredentialAttempts(new BucketPool());
    private final Reauthentication reauthentication = new Reauthentication(new PasswordVerification(attempts, credentials, hasher),
        factors, verifier, attempts, authService, new ImmediateUnitOfWork(), Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void aFreshSessionChangesAPasswordWithoutProofUnlessItWasCreatedByThePassword() {
        assertThatCode(() -> require(session(AuthMethod.PASSKEY, Duration.ofMinutes(15)), SensitiveOperation.CHANGE_PASSWORD, CredentialProof.NONE))
            .doesNotThrowAnyException();
        assertCode(() -> require(session(AuthMethod.PASSWORD, Duration.ofMinutes(1)), SensitiveOperation.CHANGE_PASSWORD, CredentialProof.NONE),
            "REAUTHENTICATION_REQUIRED");
        assertCode(() -> require(session(AuthMethod.EMAIL_CODE, Duration.ofMinutes(16)), SensitiveOperation.CHANGE_PASSWORD, CredentialProof.NONE),
            "REAUTHENTICATION_REQUIRED");
    }

    @Test
    void aWrongCurrentPasswordIsRefusedAndARightOneIsAccepted() {
        when(credentials.findPasswordHash(EMAIL)).thenReturn(Optional.of("stored"));
        when(hasher.matches("right password", "stored")).thenReturn(true);
        AuthSessionData old = session(AuthMethod.PASSWORD, Duration.ofHours(1));

        assertCode(() -> require(old, SensitiveOperation.CHANGE_PASSWORD, new CredentialProof("wrong password", null)),
            "CURRENT_PASSWORD_INVALID");
        assertThatCode(() -> require(old, SensitiveOperation.CHANGE_PASSWORD, new CredentialProof("right password", null)))
            .doesNotThrowAnyException();
    }

    @Test
    void managingTheAuthenticatorNeedsACodeUnlessTheSessionIsAFreshPasskey() {
        assertThatCode(() -> require(session(AuthMethod.PASSKEY, Duration.ZERO), SensitiveOperation.MANAGE_TOTP, CredentialProof.NONE))
            .doesNotThrowAnyException();
        assertCode(() -> require(session(AuthMethod.PASSWORD, Duration.ZERO), SensitiveOperation.MANAGE_TOTP,
            new CredentialProof("a password never replaces the code", null)), "REAUTHENTICATION_REQUIRED");
        when(verifier.verify(EMAIL, "123456")).thenReturn(Optional.of(SecondFactorMethod.TOTP));
        assertThatCode(() -> require(session(AuthMethod.PASSWORD, Duration.ZERO), SensitiveOperation.MANAGE_TOTP,
            new CredentialProof(null, "123456"))).doesNotThrowAnyException();
        assertCode(() -> require(session(AuthMethod.PASSWORD, Duration.ZERO), SensitiveOperation.MANAGE_TOTP,
            new CredentialProof(null, "654321")), "SECOND_FACTOR_INVALID");
    }

    @Test
    void addingACredentialToAnAccountWithoutPasswordOrAuthenticatorNeedsNoProof() {
        assertThatCode(() -> require(session(AuthMethod.EMAIL_CODE, Duration.ofDays(3)), SensitiveOperation.ADD_CREDENTIAL, CredentialProof.NONE))
            .doesNotThrowAnyException();
        verify(hasher, never()).matches(anyString(), anyString());
    }

    @Test
    void addingACredentialToAnOldSessionAsksForTheCodeWhenAnAuthenticatorExists() {
        when(factors.totpEnabled(EMAIL)).thenReturn(true);
        when(factors.hasPassword(EMAIL)).thenReturn(true);

        assertCode(() -> require(session(AuthMethod.EMAIL_CODE, Duration.ofHours(2)), SensitiveOperation.ADD_CREDENTIAL,
            new CredentialProof("password", null)), "REAUTHENTICATION_REQUIRED");
    }

    @Test
    void addingACredentialToAnOldSessionAsksForThePasswordWithoutAnAuthenticator() {
        when(factors.hasPassword(EMAIL)).thenReturn(true);
        when(credentials.findPasswordHash(EMAIL)).thenReturn(Optional.of("stored"));
        when(hasher.matches(any(), any())).thenReturn(false);

        assertCode(() -> require(session(AuthMethod.EMAIL_CODE, Duration.ofHours(2)), SensitiveOperation.ADD_CREDENTIAL,
            new CredentialProof("guess", null)), "CURRENT_PASSWORD_INVALID");
    }

    @Test
    void anEmailChangeOnAnAccountWithoutPasswordOrAuthenticatorNeedsACodeSentToTheCurrentAddressEvenWhenFresh() {
        AuthSessionData fresh = session(AuthMethod.PASSKEY, Duration.ZERO);
        when(authService.verifyCode(server, EMAIL, "111111")).thenReturn(true);

        assertThat(reauthentication.emailChangeProof(EMAIL)).isEqualTo(EmailChangeProof.CURRENT_ADDRESS_CODE);
        assertCode(() -> reauthentication.requireForEmailChange(server, fresh, CredentialProof.NONE, SignInClient.unidentified()),
            "REAUTHENTICATION_REQUIRED");
        assertCode(() -> reauthentication.requireForEmailChange(server, session(AuthMethod.EMAIL_CODE, Duration.ofDays(3)),
            new CredentialProof(null, "222222"), SignInClient.unidentified()), "SECOND_FACTOR_INVALID");
        assertThatCode(() -> reauthentication.requireForEmailChange(server, fresh, new CredentialProof(null, "111111"),
            SignInClient.unidentified())).doesNotThrowAnyException();
    }

    @Test
    void anEmailChangeUsesTheAuthenticatorOrThePasswordWhenTheAccountHasOne() {
        when(factors.hasPassword(EMAIL)).thenReturn(true);
        when(credentials.findPasswordHash(EMAIL)).thenReturn(Optional.of("stored"));
        when(hasher.matches("right password", "stored")).thenReturn(true);
        AuthSessionData fresh = session(AuthMethod.EMAIL_CODE, Duration.ZERO);

        assertThat(reauthentication.emailChangeProof(EMAIL)).isEqualTo(EmailChangeProof.PASSWORD);
        assertCode(() -> reauthentication.requireForEmailChange(server, fresh, CredentialProof.NONE, SignInClient.unidentified()),
            "REAUTHENTICATION_REQUIRED");
        assertThatCode(() -> reauthentication.requireForEmailChange(server, fresh, new CredentialProof("right password", null),
            SignInClient.unidentified())).doesNotThrowAnyException();

        when(factors.totpEnabled(EMAIL)).thenReturn(true);
        assertThat(reauthentication.emailChangeProof(EMAIL)).isEqualTo(EmailChangeProof.AUTHENTICATOR);
        assertCode(() -> reauthentication.requireForEmailChange(server, fresh, new CredentialProof("right password", null),
            SignInClient.unidentified()), "REAUTHENTICATION_REQUIRED");
        verify(authService, never()).verifyCode(any(), anyString(), anyString());
    }

    @Test
    void theFifteenMinuteEdgeIsStillFresh() {
        assertThat(reauthentication.isFresh(session(AuthMethod.EMAIL_CODE, Duration.ofMinutes(15)))).isTrue();
        assertThat(reauthentication.isFresh(session(AuthMethod.EMAIL_CODE, Duration.ofMinutes(15).plusMillis(1)))).isFalse();
    }

    private void require(AuthSessionData session, SensitiveOperation operation, CredentialProof proof) {
        reauthentication.require(session, operation, proof, SignInClient.unidentified());
    }

    private static AuthSessionData session(AuthMethod method, Duration age) {
        return new AuthSessionData("id", EMAIL, NOW.minus(age), NOW.plusSeconds(60), null, null, "digest", method, false);
    }

    private static void assertCode(Runnable call, String code) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(BaseApplicationException.class,
            failure -> assertThat(failure.getMachineCode()).isEqualTo(code));
    }
}

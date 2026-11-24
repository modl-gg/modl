package gg.modl.backend.auth.signin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import gg.modl.backend.auth.AccountAuthorization;
import gg.modl.backend.auth.ImmediateUnitOfWork;
import gg.modl.backend.auth.repository.StaffCredentialRepository;
import gg.modl.backend.auth.session.AuthMethod;
import gg.modl.backend.auth.session.AuthSessionData;
import gg.modl.backend.auth.session.IssuedSession;
import gg.modl.backend.auth.session.SessionService;
import gg.modl.backend.auth.settings.SignInMethod;
import gg.modl.backend.auth.settings.SignInSettings;
import gg.modl.backend.auth.settings.SignInSettingsStub;
import gg.modl.backend.auth.totp.SecondFactorMethod;
import gg.modl.backend.auth.totp.SecondFactorVerifier;
import gg.modl.backend.infrastructure.exception.UnauthorizedException;
import gg.modl.backend.infrastructure.exception.ValidationException;
import gg.modl.backend.infrastructure.ratelimit.BucketPool;
import gg.modl.backend.infrastructure.util.IdGenerator;
import gg.modl.backend.log.service.PanelActionAuditor;
import gg.modl.backend.server.data.Server;
import gg.modl.backend.staff.data.Staff;
import gg.modl.backend.staff.repository.StaffRepository;
import gg.modl.backend.support.TestServers;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class SignInCompletionTest {
    private static final Server SERVER = TestServers.server("Alpha", "owner@example.com");
    private static final String EMAIL = "mod@example.com";
    private static final Staff MEMBER = Staff.builder().id("staff-1").email(EMAIL).build();
    private static final SignInClient CLIENT = SignInClient.unidentified();

    private final SessionService sessionService = mock(SessionService.class);
    private final StaffRepository staffRepository = mock(StaffRepository.class);
    private final StaffCredentialRepository credentialRepository = mock(StaffCredentialRepository.class);
    private final AccountFactors factors = mock(AccountFactors.class);
    private final SecondFactorVerifier verifier = mock(SecondFactorVerifier.class);
    private final PanelActionAuditor auditor = mock(PanelActionAuditor.class);
    private final SecondFactorChallenges challenges = new SecondFactorChallenges(new IdGenerator());

    @BeforeEach
    void currentMember() {
        when(staffRepository.findByEmail(EMAIL)).thenReturn(Optional.of(MEMBER));
        when(staffRepository.findByIdForUpdate("staff-1")).thenReturn(Optional.of(MEMBER));
        when(sessionService.createSession(any(), any(), any(), anyBoolean(), any(), any())).thenAnswer(call -> new IssuedSession(
            new AuthSessionData("id", call.getArgument(1), Instant.now(), Instant.now(), null, null, "digest", call.getArgument(2),
                call.getArgument(3)), "key"));
    }

    @Test
    void aPasskeyAloneIssuesAVerifiedSessionEvenWithAnAuthenticator() {
        when(factors.totpEnabled(EMAIL)).thenReturn(true);

        SignInOutcome outcome = completion(false).complete(SERVER, SignInAttempt.of(EMAIL, AuthMethod.PASSKEY), CLIENT);

        assertThat(outcome).isInstanceOfSatisfying(SignInOutcome.Issued.class,
            issued -> assertThat(issued.session().session().isSecondFactorVerified()).isTrue());
    }

    @ParameterizedTest
    @EnumSource(value = AuthMethod.class, names = "PASSKEY", mode = EnumSource.Mode.EXCLUDE)
    void everyOtherFirstFactorIsChallengedWhenAnAuthenticatorIsSetUp(AuthMethod method) {
        when(factors.totpEnabled(EMAIL)).thenReturn(true);
        when(factors.passkeyCount(EMAIL)).thenReturn(1);

        SignInOutcome outcome = completion(false).complete(SERVER, SignInAttempt.of(EMAIL, method), CLIENT);

        assertThat(outcome).isInstanceOfSatisfying(SignInOutcome.Challenged.class, challenged -> assertThat(challenged.challenge().methods())
            .containsExactly(SecondFactorMethod.TOTP, SecondFactorMethod.RECOVERY_CODE, SecondFactorMethod.PASSKEY));
        verify(sessionService, never()).createSession(any(), any(), any(), anyBoolean(), any(), any());
    }

    @Test
    void underEnforcementAPasskeyHolderMustUseThePasskey() {
        when(factors.passkeyCount(EMAIL)).thenReturn(2);

        SignInOutcome outcome = completion(true).complete(SERVER, SignInAttempt.of(EMAIL, AuthMethod.EMAIL_CODE), CLIENT);

        assertThat(outcome).isInstanceOfSatisfying(SignInOutcome.Challenged.class, challenged -> {
            assertThat(challenged.challenge().methods()).containsExactly(SecondFactorMethod.PASSKEY);
            assertThat(challenged.challenge().acceptsCode()).isFalse();
        });
    }

    @Test
    void withoutAnyFactorTheSessionIsIssuedUnverified() {
        assertThat(completion(true).complete(SERVER, SignInAttempt.of(EMAIL, AuthMethod.EMAIL_CODE), CLIENT))
            .isInstanceOfSatisfying(SignInOutcome.Issued.class,
                issued -> assertThat(issued.session().session().isSecondFactorVerified()).isFalse());
        assertThat(completion(false).complete(SERVER, SignInAttempt.of(EMAIL, AuthMethod.LOGIN_LINK), CLIENT))
            .isInstanceOf(SignInOutcome.Issued.class);
    }

    @Test
    void aPasswordChangedBetweenVerifyAndIssueGetsNoSession() {
        when(credentialRepository.findPasswordHash(EMAIL)).thenReturn(Optional.of("new-hash"));

        assertThatThrownBy(() -> completion(false).complete(SERVER, new SignInAttempt(EMAIL, AuthMethod.PASSWORD, "old-hash"), CLIENT))
            .isInstanceOfSatisfying(UnauthorizedException.class,
                refused -> assertThat(refused.getMachineCode()).isEqualTo("INVALID_CREDENTIALS"));
    }

    @Test
    void aRemovedStaffMemberGetsNoSession() {
        when(staffRepository.findByIdForUpdate("staff-1")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> completion(false).complete(SERVER, SignInAttempt.of(EMAIL, AuthMethod.EMAIL_CODE), CLIENT))
            .isInstanceOf(UnauthorizedException.class);
    }

    @Test
    void theOwnerNeedsNoStaffRow() {
        assertThat(completion(false).complete(SERVER, SignInAttempt.of("Owner@Example.com", AuthMethod.SETUP_CODE), CLIENT))
            .isInstanceOf(SignInOutcome.Issued.class);
        verify(staffRepository, never()).findByEmail("owner@example.com");
    }

    @Test
    void aCorrectCodeCompletesTheChallengeOnceWithAVerifiedSessionOfTheOriginalMethod() {
        when(factors.totpEnabled(EMAIL)).thenReturn(true);
        SignInOutcome.Challenged challenged = (SignInOutcome.Challenged) completion(false)
            .complete(SERVER, SignInAttempt.of(EMAIL, AuthMethod.LOGIN_LINK), CLIENT);
        when(verifier.verify(EMAIL, "123456")).thenReturn(Optional.of(SecondFactorMethod.TOTP));

        IssuedSession issued = completion(false).completeChallenge(SERVER, challenged.challenge().token(), "123456", CLIENT);

        assertThat(issued.session().isSecondFactorVerified()).isTrue();
        assertThat(issued.session().getAuthMethod()).isEqualTo(AuthMethod.LOGIN_LINK);
        assertThatThrownBy(() -> completion(false).completeChallenge(SERVER, challenged.challenge().token(), "123456", CLIENT))
            .isInstanceOfSatisfying(ValidationException.class,
                expired -> assertThat(expired.getMachineCode()).isEqualTo("SIGN_IN_CHALLENGE_EXPIRED"));
    }

    @Test
    void aWrongCodeKeepsTheChallengeUntilFiveFailures() {
        when(factors.totpEnabled(EMAIL)).thenReturn(true);
        String token = ((SignInOutcome.Challenged) completion(false)
            .complete(SERVER, SignInAttempt.of(EMAIL, AuthMethod.EMAIL_CODE), CLIENT)).challenge().token();
        when(verifier.verify(eq(EMAIL), any())).thenReturn(Optional.empty());

        for (int attempt = 0; attempt < SecondFactorChallenges.MAX_FAILURES; attempt++) {
            assertThatThrownBy(() -> completion(false).completeChallenge(SERVER, token, "000000", CLIENT))
                .isInstanceOfSatisfying(ValidationException.class,
                    invalid -> assertThat(invalid.getMachineCode()).isIn("SECOND_FACTOR_INVALID", "SIGN_IN_CHALLENGE_EXPIRED"));
        }
        assertThat(challenges.find(token)).isEmpty();
    }

    @Test
    void aRecoveryCodeSignInIsAudited() {
        when(factors.totpEnabled(EMAIL)).thenReturn(true);
        String token = ((SignInOutcome.Challenged) completion(false)
            .complete(SERVER, SignInAttempt.of(EMAIL, AuthMethod.PASSWORD), CLIENT)).challenge().token();
        when(verifier.verify(EMAIL, "ABCD-EFGH-JKLM")).thenReturn(Optional.of(SecondFactorMethod.RECOVERY_CODE));

        completion(false).completeChallenge(SERVER, token, "ABCD-EFGH-JKLM", CLIENT);

        verify(auditor).recordStaffAction(SERVER, EMAIL, "Signed in with a two-factor recovery code");
    }

    private SignInCompletion completion(boolean enforced) {
        return new SignInCompletion(sessionService, new AccountAuthorization(staffRepository), credentialRepository, factors,
            SignInSettingsStub.with(new SignInSettings(SignInMethod.EMAIL_CODE, enforced)), challenges, verifier,
            new CredentialAttempts(new BucketPool()), auditor, new ImmediateUnitOfWork());
    }
}

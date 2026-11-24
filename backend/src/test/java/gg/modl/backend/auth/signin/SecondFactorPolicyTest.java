package gg.modl.backend.auth.signin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import gg.modl.backend.auth.session.AuthMethod;
import gg.modl.backend.auth.session.AuthSessionData;
import gg.modl.backend.auth.settings.SignInMethod;
import gg.modl.backend.auth.settings.SignInSettings;
import gg.modl.backend.auth.settings.SignInSettingsStub;
import gg.modl.backend.infrastructure.rest.RouteGroups;
import java.time.Instant;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.Test;

class SecondFactorPolicyTest {
    private static final String EMAIL = "mod@example.com";

    private final AccountFactors factors = mock(AccountFactors.class);

    @Test
    void withoutEnforcementEverySessionIsSatisfied() {
        SecondFactorPolicy policy = new SecondFactorPolicy(SignInSettingsStub.emailCode(), factors);

        assertThat(policy.state(session(false))).isEqualTo(SecondFactorState.SATISFIED);
        assertThat(policy.decide(session(false), RouteGroups.classifyPath("/v1/panel/tickets"), "GET")).isEqualTo(SecondFactorDecision.ADMIT);
    }

    @Test
    void aVerifiedSessionIsSatisfiedEvenWhileEnforced() {
        assertThat(enforced().state(session(true))).isEqualTo(SecondFactorState.SATISFIED);
    }

    @ParameterizedTest
    @CsvSource({
        "GET, /v1/panel/auth/me, false, ADMIT",
        "GET, /v1/panel/auth/security, false, ADMIT",
        "POST, /v1/panel/auth/logout, false, ADMIT",
        "DELETE, /v1/panel/auth/sessions, false, ADMIT",
        "POST, /v1/panel/auth/totp, false, ADMIT",
        "POST, /v1/panel/auth/totp/confirm, false, ADMIT",
        "POST, /v1/panel/auth/webauthn/register/options, false, ADMIT",
        "POST, /v1/panel/auth/webauthn/register/verify, false, ADMIT",
        "GET, /v1/panel/tickets, false, ENROLLMENT_REQUIRED",
        "GET, /v1/panel/auth/permissions, false, ENROLLMENT_REQUIRED",
        "POST, /v1/panel/auth/password/login, false, ANONYMOUS",
        "POST, /v1/panel/auth/webauthn/login/verify, false, ANONYMOUS",
        "GET, /v1/public/tickets/t1, false, ANONYMOUS",
        "GET, /v1/panel/auth/me, true, ADMIT",
        "POST, /v1/panel/auth/totp, true, VERIFICATION_REQUIRED",
        "POST, /v1/panel/auth/webauthn/register/options, true, VERIFICATION_REQUIRED",
        "GET, /v1/panel/tickets, true, VERIFICATION_REQUIRED",
        "GET, /v1/public/settings, true, ANONYMOUS"
    })
    void restrictedSessionsReachOnlyTheirAllowlist(String method, String path, boolean hasFactor, SecondFactorDecision expected) {
        when(factors.hasSecondFactor(EMAIL)).thenReturn(hasFactor);

        assertThat(enforced().decide(session(false), RouteGroups.classifyPath(path), method)).isEqualTo(expected);
    }

    @Test
    void anEncodedPanelPathIsStillRefused() {
        when(factors.hasSecondFactor(EMAIL)).thenReturn(false);

        assertThat(enforced().decide(session(false), RouteGroups.classifyPath("/v1/%70anel/tickets"), "GET"))
            .isEqualTo(SecondFactorDecision.ENROLLMENT_REQUIRED);
    }

    private SecondFactorPolicy enforced() {
        return new SecondFactorPolicy(SignInSettingsStub.with(new SignInSettings(SignInMethod.EMAIL_CODE, true)), factors);
    }

    private static AuthSessionData session(boolean verified) {
        return new AuthSessionData("id", EMAIL, Instant.now(), Instant.now(), null, null, "digest", AuthMethod.PASSWORD, verified);
    }
}

package gg.modl.backend.instance.setup;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import gg.modl.backend.auth.AuthFailures;
import gg.modl.backend.auth.settings.SignInMethod;
import gg.modl.backend.auth.settings.SignInSettings;
import gg.modl.backend.auth.settings.SignInSettingsService;
import gg.modl.backend.auth.signin.AccountFactors;
import gg.modl.backend.infrastructure.exception.ConflictException;
import gg.modl.backend.server.data.Server;
import gg.modl.backend.support.TestServers;
import org.junit.jupiter.api.Test;

class OwnerSignInReadinessTest {
    private static final Server OWNED = TestServers.server("Server", "Owner@Example.com");

    private final SignInSettingsService signInSettings = mock(SignInSettingsService.class);
    private final AccountFactors accountFactors = mock(AccountFactors.class);
    private final OwnerSignInReadiness readiness = new OwnerSignInReadiness(signInSettings, accountFactors);

    @Test
    void emailCodesAlwaysLetTheOwnerSignInAgain() {
        when(signInSettings.stored()).thenReturn(new SignInSettings(SignInMethod.EMAIL_CODE, false));

        assertThatCode(() -> readiness.require(OWNED)).doesNotThrowAnyException();
    }

    @Test
    void passwordSignInNeedsTheOwnersPassword() {
        when(signInSettings.stored()).thenReturn(new SignInSettings(SignInMethod.PASSWORD, false));
        when(accountFactors.hasPassword("owner@example.com")).thenReturn(false);

        assertThatThrownBy(() -> readiness.require(OWNED))
            .isInstanceOf(ConflictException.class)
            .extracting(failure -> ((ConflictException) failure).getMachineCode())
            .isEqualTo(AuthFailures.OWNER_PASSWORD_REQUIRED);
    }

    @Test
    void passwordSignInWithTheOwnersPasswordIsReady() {
        when(signInSettings.stored()).thenReturn(new SignInSettings(SignInMethod.PASSWORD, false));
        when(accountFactors.hasPassword("owner@example.com")).thenReturn(true);

        assertThatCode(() -> readiness.require(OWNED)).doesNotThrowAnyException();
    }
}

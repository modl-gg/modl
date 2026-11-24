package gg.modl.backend.auth.password;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import gg.modl.backend.auth.AuthFailures;
import gg.modl.backend.auth.repository.StaffCredentialRepository;
import gg.modl.backend.auth.signin.CredentialAttempts;
import gg.modl.backend.auth.signin.SignInClient;
import gg.modl.backend.infrastructure.exception.UnauthorizedException;
import gg.modl.backend.infrastructure.ratelimit.BucketPool;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class PasswordVerificationTest {
    private static final String EMAIL = "mod@example.com";

    private final StaffCredentialRepository credentials = mock(StaffCredentialRepository.class);
    private final PasswordHasher hasher = mock(PasswordHasher.class);
    private final PasswordVerification verification =
        new PasswordVerification(new CredentialAttempts(new BucketPool()), credentials, hasher);

    @Test
    void anUnknownAccountCostsExactlyOneHashAndIsRefused() {
        when(credentials.findPasswordHash(EMAIL)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> check(true)).isInstanceOf(UnauthorizedException.class);

        verify(hasher, times(1)).burnForUnknownAccount("secret");
        verify(hasher, never()).matches(anyString(), anyString());
    }

    @Test
    void theRightPasswordOfAnIneligibleAccountIsRefusedLikeAWrongOne() {
        when(credentials.findPasswordHash(EMAIL)).thenReturn(Optional.of("stored"));
        when(hasher.matches("secret", "stored")).thenReturn(true);

        assertThatThrownBy(() -> check(false)).isInstanceOf(UnauthorizedException.class);
        assertThat(check(true)).isEqualTo("stored");
        verify(hasher, times(2)).matches("secret", "stored");
    }

    @Test
    void anInfrastructureFailureIsPassedThrough() {
        when(credentials.findPasswordHash(EMAIL)).thenThrow(new IllegalStateException("database down"));

        assertThatThrownBy(() -> check(true)).hasMessage("database down");
        verify(hasher, never()).matches(anyString(), anyString());
    }

    private String check(boolean eligible) {
        return verification.verify(EMAIL, "secret", SignInClient.unidentified(), () -> eligible, AuthFailures::invalidCredentials);
    }
}

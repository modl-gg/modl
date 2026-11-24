package gg.modl.backend.auth.signin;

import gg.modl.backend.auth.settings.SignInMethod;
import org.jspecify.annotations.Nullable;
import java.time.Instant;

public record AccountSecurity(
    SignInMethod method,
    boolean hasPassword,
    boolean totpEnabled,
    @Nullable Instant totpEnabledAt,
    int recoveryCodesRemaining,
    int passkeyCount,
    boolean secondFactorRequired,
    boolean sessionVerified,
    SecondFactorState state
) {
}

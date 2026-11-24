package gg.modl.backend.auth.signin;

import gg.modl.backend.auth.AuthFailures;
import org.jspecify.annotations.Nullable;

public enum SecondFactorDecision {
    ADMIT(null, null),
    ANONYMOUS(null, null),
    ENROLLMENT_REQUIRED(AuthFailures.SECOND_FACTOR_ENROLLMENT_REQUIRED, "Set up two-factor authentication to continue"),
    VERIFICATION_REQUIRED(AuthFailures.SECOND_FACTOR_REQUIRED, "Sign in again with your second factor to continue");

    @Nullable
    private final String code;
    @Nullable
    private final String message;

    SecondFactorDecision(@Nullable String code, @Nullable String message) {
        this.code = code;
        this.message = message;
    }

    @Nullable
    public String code() {
        return code;
    }

    @Nullable
    public String message() {
        return message;
    }
}

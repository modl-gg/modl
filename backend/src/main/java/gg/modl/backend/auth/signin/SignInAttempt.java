package gg.modl.backend.auth.signin;

import gg.modl.backend.auth.session.AuthMethod;
import org.jspecify.annotations.Nullable;

public record SignInAttempt(String email, AuthMethod method, @Nullable String verifiedPasswordHash) {
    public static SignInAttempt of(String email, AuthMethod method) {
        return new SignInAttempt(email, method, null);
    }

    @Override
    public String toString() {
        return "SignInAttempt[email=" + email + ", method=" + method + "]";
    }
}

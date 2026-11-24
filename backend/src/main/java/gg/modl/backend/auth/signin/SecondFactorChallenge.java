package gg.modl.backend.auth.signin;

import gg.modl.backend.auth.session.AuthMethod;
import gg.modl.backend.auth.totp.SecondFactorMethod;
import org.jspecify.annotations.Nullable;
import java.util.List;

public record SecondFactorChallenge(
    String token,
    String email,
    AuthMethod method,
    @Nullable String expectedPasswordHash,
    List<SecondFactorMethod> methods,
    long expiresInSeconds
) {
    public SecondFactorChallenge {
        methods = List.copyOf(methods);
    }

    public boolean acceptsCode() {
        return methods.contains(SecondFactorMethod.TOTP);
    }

    @Override
    public String toString() {
        return "SecondFactorChallenge[email=" + email + ", method=" + method + ", methods=" + methods + "]";
    }
}

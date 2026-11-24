package gg.modl.backend.auth.signin;

import org.jspecify.annotations.Nullable;
import java.util.Optional;

public record SignInClient(@Nullable String ipAddress, @Nullable String userAgent, Optional<String> identifiedNetwork) {
    public static SignInClient unidentified() {
        return new SignInClient(null, null, Optional.empty());
    }
}

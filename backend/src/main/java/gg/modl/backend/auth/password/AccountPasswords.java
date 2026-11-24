package gg.modl.backend.auth.password;

import gg.modl.backend.auth.AuthFailures;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class AccountPasswords {
    private final PasswordHasher passwordHasher;

    public PreparedPassword prepare(String rawPassword) {
        return new PreparedPassword(passwordHasher.hash(PasswordPolicy.requireAcceptable(rawPassword)));
    }

    public PreparedPassword prepareRequired(@Nullable String rawPassword) {
        if (rawPassword == null || rawPassword.isEmpty()) {
            throw AuthFailures.passwordRequired();
        }
        return prepare(rawPassword);
    }

    public Optional<PreparedPassword> prepareIfPresent(@Nullable String rawPassword) {
        return rawPassword == null || rawPassword.isEmpty() ? Optional.empty() : Optional.of(prepare(rawPassword));
    }
}

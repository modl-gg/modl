package gg.modl.backend.auth.signin;

import org.jspecify.annotations.Nullable;
import java.util.Optional;

public record CredentialProof(@Nullable String currentPassword, @Nullable String code) {
    public static final CredentialProof NONE = new CredentialProof(null, null);

    public Optional<String> password() {
        return Optional.ofNullable(currentPassword).filter(value -> !value.isEmpty());
    }

    public Optional<String> secondFactorCode() {
        return Optional.ofNullable(code).filter(value -> !value.isBlank());
    }

    @Override
    public String toString() {
        return "CredentialProof[redacted]";
    }
}

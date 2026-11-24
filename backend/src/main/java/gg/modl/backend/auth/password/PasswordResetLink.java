package gg.modl.backend.auth.password;

import java.time.Instant;

public record PasswordResetLink(String url, String email, Instant expiresAt) {
    @Override
    public String toString() {
        return "PasswordResetLink[email=" + email + ", expiresAt=" + expiresAt + "]";
    }
}

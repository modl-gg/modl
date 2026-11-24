package gg.modl.backend.auth.repository;

import java.time.Instant;

public record PasswordResetToken(String email, Instant expiresAt) {
}

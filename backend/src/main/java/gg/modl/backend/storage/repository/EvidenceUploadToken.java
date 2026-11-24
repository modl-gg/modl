package gg.modl.backend.storage.repository;

import org.jspecify.annotations.Nullable;

import java.time.Instant;

public record EvidenceUploadToken(
    String tokenHash,
    String punishmentId,
    String playerUuid,
    @Nullable String issuerName,
    Instant createdAt,
    Instant expiresAt
) {
}

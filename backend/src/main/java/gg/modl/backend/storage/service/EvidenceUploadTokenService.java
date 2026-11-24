package gg.modl.backend.storage.service;

import gg.modl.backend.infrastructure.util.DigestUtils;
import gg.modl.backend.server.data.Server;
import gg.modl.backend.storage.repository.EvidenceUploadToken;
import gg.modl.backend.storage.repository.EvidenceUploadTokenRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class EvidenceUploadTokenService {
    private static final Duration TOKEN_TTL = Duration.ofMinutes(30);
    private static final String UNKNOWN_ISSUER = "Unknown";

    private final EvidenceUploadTokenRepository tokenRepository;
    private final Clock clock;

    public String createToken(Server server, String punishmentId, String playerUuid, @Nullable String issuerName) {
        String token = UUID.randomUUID().toString();
        Instant now = clock.instant();
        tokenRepository.insert(new EvidenceUploadToken(
            DigestUtils.sha256Hex(token),
            punishmentId,
            playerUuid,
            issuerOrUnknown(issuerName),
            now,
            now.plus(TOKEN_TTL)
        ));
        return token;
    }

    @Nullable
    public UploadToken validateToken(String token) {
        Instant now = clock.instant();
        return tokenRepository.findByTokenHash(DigestUtils.sha256Hex(token))
            .filter(stored -> !stored.expiresAt().isBefore(now))
            .map(stored -> new UploadToken(
                token,
                stored.punishmentId(),
                stored.playerUuid(),
                issuerOrUnknown(stored.issuerName()),
                stored.createdAt()
            ))
            .orElse(null);
    }

    private static String issuerOrUnknown(@Nullable String issuerName) {
        return issuerName != null ? issuerName : UNKNOWN_ISSUER;
    }

    public boolean invalidateToken(String token) {
        return tokenRepository.deleteByTokenHash(DigestUtils.sha256Hex(token));
    }

    public record UploadToken(
        String token,
        String punishmentId,
        String playerUuid,
        String issuerName,
        Instant createdAt
    ) {
    }
}

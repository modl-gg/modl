package gg.modl.backend.instance.setup;

import java.time.Instant;

public record IssuedSetupCode(String codeHash, Instant issuedAt, Instant expiresAt) {
}

package gg.modl.backend.player.repository;

import org.jspecify.annotations.Nullable;

public record IssuerMatch(@Nullable String issuerName, @Nullable String issuerId) {
    public IssuerMatch {
        if (issuerName == null && issuerId == null) {
            throw new IllegalArgumentException("An issuer name or id is required");
        }
    }
}

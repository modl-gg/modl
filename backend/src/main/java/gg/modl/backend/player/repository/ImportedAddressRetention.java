package gg.modl.backend.player.repository;

import org.jspecify.annotations.Nullable;
import java.time.Instant;

public enum ImportedAddressRetention {
    STANDARD(null),
    KEPT(Instant.parse("9999-12-31T23:59:59.999Z"));

    private final @Nullable Instant retainedUntil;

    ImportedAddressRetention(@Nullable Instant retainedUntil) {
        this.retainedUntil = retainedUntil;
    }

    public @Nullable Instant retainedUntil() {
        return retainedUntil;
    }
}

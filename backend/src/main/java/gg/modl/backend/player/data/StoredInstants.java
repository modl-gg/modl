package gg.modl.backend.player.data;

import org.jspecify.annotations.Nullable;
import java.time.Instant;

public final class StoredInstants {
    private StoredInstants() {
    }

    @Nullable
    public static Instant of(@Nullable Object value) {
        return value instanceof Instant instant ? instant : null;
    }
}

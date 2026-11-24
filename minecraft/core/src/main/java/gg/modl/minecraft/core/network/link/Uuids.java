package gg.modl.minecraft.core.network.link;

import org.jspecify.annotations.Nullable;

import java.util.UUID;

final class Uuids {
    private Uuids() {
    }

    static @Nullable UUID parse(@Nullable String value) {
        if (value == null || value.isEmpty()) return null;
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException malformed) {
            return null;
        }
    }
}

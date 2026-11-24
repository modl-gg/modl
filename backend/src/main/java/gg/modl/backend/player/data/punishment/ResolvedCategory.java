package gg.modl.backend.player.data.punishment;

import org.jspecify.annotations.Nullable;

public enum ResolvedCategory {
    BAN, MUTE, NONE;

    public static ResolvedCategory fromStored(@Nullable String stored) {
        if (BAN.name().equals(stored)) {
            return BAN;
        }
        if (MUTE.name().equals(stored)) {
            return MUTE;
        }
        return NONE;
    }

    public boolean isEnforced() {
        return this != NONE;
    }

    @Nullable
    public String displayName() {
        return isEnforced() ? name() : null;
    }
}

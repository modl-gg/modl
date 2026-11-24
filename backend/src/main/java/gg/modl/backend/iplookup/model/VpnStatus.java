package gg.modl.backend.iplookup.model;

import org.jspecify.annotations.Nullable;
import java.util.Arrays;

public enum VpnStatus {
    YES,
    NO,
    UNKNOWN;

    public boolean isKnownClean() {
        return this == NO;
    }

    public static VpnStatus fromStored(@Nullable String stored) {
        return Arrays.stream(values()).filter(status -> status.name().equals(stored)).findFirst().orElse(UNKNOWN);
    }
}

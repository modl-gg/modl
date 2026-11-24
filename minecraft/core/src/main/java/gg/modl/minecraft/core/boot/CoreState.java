package gg.modl.minecraft.core.boot;

import org.jspecify.annotations.Nullable;

public enum CoreState {
    STARTING("api_errors.connection_failed"),
    RUNNING(null),
    UNAVAILABLE("api_errors.startup_failed"),
    INCOMPATIBLE("api_errors.version_mismatch");

    private final @Nullable String refusalMessageKey;

    CoreState(@Nullable String refusalMessageKey) {
        this.refusalMessageKey = refusalMessageKey;
    }

    public boolean isRunning() {
        return this == RUNNING;
    }

    public String refusalMessageKey() {
        String key = refusalMessageKey;
        if (key == null) throw new IllegalStateException("a running modl core refuses nothing");
        return key;
    }
}

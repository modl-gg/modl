package gg.modl.backend.tunnel;

import org.jspecify.annotations.Nullable;

public record TunnelState(TunnelPhase phase, @Nullable String problem) {
    public static final TunnelState OFF = new TunnelState(TunnelPhase.OFF, null);
    public static final TunnelState CONNECTING = new TunnelState(TunnelPhase.CONNECTING, null);

    public static TunnelState unavailable(String problem) {
        return new TunnelState(TunnelPhase.UNAVAILABLE, problem);
    }
}

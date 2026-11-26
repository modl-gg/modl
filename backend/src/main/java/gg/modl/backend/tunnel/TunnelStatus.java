package gg.modl.backend.tunnel;

public interface TunnelStatus {
    boolean enabled();

    TunnelState state();

    String address();
}

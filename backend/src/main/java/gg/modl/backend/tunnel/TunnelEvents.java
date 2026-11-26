package gg.modl.backend.tunnel;

public interface TunnelEvents {
    void connected();

    void notConnected();

    void unavailable(String reason);
}

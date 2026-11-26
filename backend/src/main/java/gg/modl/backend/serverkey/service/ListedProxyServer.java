package gg.modl.backend.serverkey.service;

import gg.modl.backend.network.ProxyServer;
import org.jspecify.annotations.Nullable;

public record ListedProxyServer(ProxyServer server, State state, @Nullable String keyId) {
    public enum State { NO_KEY, NOT_CONNECTED, CONNECTED }
}

package gg.modl.backend.network;

import gg.modl.proto.modl.v1.ServerRole;
import org.jspecify.annotations.Nullable;
import java.time.Instant;
import java.util.List;

public record LiveNetworkSession(
    String instanceId,
    String name,
    ServerRole role,
    @Nullable String keyName,
    String pluginVersion,
    int onlinePlayers,
    Instant connectedAt,
    List<ProxyServer> proxyServers,
    @Nullable FeatureStatus features
) {
}

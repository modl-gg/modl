package gg.modl.backend.network;

import gg.modl.proto.modl.v1.ServerRole;
import org.jspecify.annotations.Nullable;
import java.time.Instant;

public record LiveNetworkSession(
    String instanceId,
    String name,
    ServerRole role,
    @Nullable String keyName,
    String pluginVersion,
    int onlinePlayers,
    Instant connectedAt
) {
}

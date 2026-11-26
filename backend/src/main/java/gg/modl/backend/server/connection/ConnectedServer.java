package gg.modl.backend.server.connection;

import gg.modl.backend.network.FeatureStatus;
import gg.modl.backend.network.LiveNetworkSession;
import gg.modl.proto.modl.v1.ServerRole;
import java.time.Instant;
import org.jspecify.annotations.Nullable;
import gg.modl.backend.update.compat.PluginCompatibilityPolicy;
import gg.modl.backend.update.compat.RefusedServers;
import gg.modl.update.VersionCompatibility;

public record ConnectedServer(
    String instanceId,
    String name,
    String platform,
    String pluginVersion,
    String serverVersion,
    int onlinePlayers,
    Instant lastSeen,
    ServerRole role,
    @Nullable String keyName,
    boolean hubConnected,
    VersionCompatibility compatibility,
    @Nullable FeatureStatus features
) {
    static ConnectedServer started(String instanceId, ServerStartupReport report, Instant now) {
        return new ConnectedServer(instanceId, report.keyName() != null ? report.keyName() : "", report.platform(),
            report.pluginVersion(), report.serverVersion(), 0, now, report.role(), report.keyName(), false,
            VersionCompatibility.UNKNOWN, null);
    }

    static ConnectedServer firstHeartbeat(ServerHeartbeat heartbeat, Instant now) {
        return new ConnectedServer(heartbeat.instanceId(), "", "", "", "", 0, now, ServerRole.SERVER_ROLE_CORE, null, false,
            VersionCompatibility.UNKNOWN, null)
            .withHeartbeat(heartbeat, now);
    }

    ConnectedServer withHeartbeat(ServerHeartbeat heartbeat, Instant now) {
        return new ConnectedServer(instanceId, heartbeat.name().isEmpty() ? name : heartbeat.name(), platform,
            heartbeat.pluginVersion().isEmpty() ? pluginVersion : heartbeat.pluginVersion(), serverVersion,
            heartbeat.onlinePlayers(), now, role, keyName, hubConnected, compatibility, features);
    }

    ConnectedServer withHub(LiveNetworkSession live, Instant now) {
        return new ConnectedServer(instanceId, live.name().isEmpty() ? name : live.name(), platform,
            live.pluginVersion().isEmpty() ? pluginVersion : live.pluginVersion(), serverVersion,
            live.role() == ServerRole.SERVER_ROLE_MEMBER ? live.onlinePlayers() : onlinePlayers, now, live.role(),
            live.keyName() != null ? live.keyName() : keyName, true, compatibility, live.features());
    }

    static ConnectedServer fromHub(LiveNetworkSession live, Instant now) {
        return new ConnectedServer(live.instanceId(), live.name(), "", live.pluginVersion(), "", live.onlinePlayers(), now,
            live.role(), live.keyName(), true, VersionCompatibility.UNKNOWN, live.features());
    }

    static ConnectedServer refused(RefusedServers.RefusedServer refused) {
        return new ConnectedServer(refused.bootId(), "", refused.platform(), refused.pluginVersion(), "", 0, refused.refusedAt(),
            ServerRole.SERVER_ROLE_UNSPECIFIED, null, false, refused.compatibility(), null);
    }

    ConnectedServer judgedBy(PluginCompatibilityPolicy policy) {
        if (!compatibility.isCompatible() && compatibility != VersionCompatibility.UNKNOWN) {
            return this;
        }
        return new ConnectedServer(instanceId, name, platform, pluginVersion, serverVersion, onlinePlayers, lastSeen, role, keyName,
            hubConnected, pluginVersion.isEmpty() ? VersionCompatibility.UNKNOWN : policy.compatibilityOf(pluginVersion), features);
    }
}

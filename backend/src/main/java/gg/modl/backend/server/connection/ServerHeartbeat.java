package gg.modl.backend.server.connection;

import org.jspecify.annotations.Nullable;

public record ServerHeartbeat(String instanceId, String name, int onlinePlayers, String pluginVersion) {

    public ServerHeartbeat {
        instanceId = ReportedText.clean(instanceId);
        name = ReportedText.clean(name);
        onlinePlayers = Math.max(0, onlinePlayers);
        pluginVersion = ReportedText.clean(pluginVersion);
    }

    public ServerHeartbeat(String instanceId, String name, int onlinePlayers) {
        this(instanceId, name, onlinePlayers, "");
    }

    public static ServerHeartbeat reported(@Nullable String instanceId, @Nullable String name, int onlinePlayers,
                                           @Nullable String pluginVersion) {
        return new ServerHeartbeat(ReportedText.clean(instanceId), ReportedText.clean(name), onlinePlayers,
            ReportedText.clean(pluginVersion));
    }
}

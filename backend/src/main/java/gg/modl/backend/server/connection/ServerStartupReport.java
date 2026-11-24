package gg.modl.backend.server.connection;

import gg.modl.proto.modl.v1.ServerRole;
import org.jspecify.annotations.Nullable;

public record ServerStartupReport(String pluginVersion, String platform, String serverVersion, ServerRole role,
                                  @Nullable String keyName) {
    public ServerStartupReport {
        pluginVersion = ReportedText.clean(pluginVersion);
        platform = ReportedText.clean(platform);
        serverVersion = ReportedText.clean(serverVersion);
        role = role == null ? ServerRole.SERVER_ROLE_UNSPECIFIED : role;
    }

    public static ServerStartupReport of(@Nullable String pluginVersion, @Nullable String platform, @Nullable String serverVersion,
                                         ServerRole role, @Nullable String keyName) {
        return new ServerStartupReport(ReportedText.clean(pluginVersion), ReportedText.clean(platform),
            ReportedText.clean(serverVersion), role, keyName);
    }
}

package gg.modl.backend.realtime.auth;

import gg.modl.backend.instance.MinecraftCredential;
import gg.modl.backend.server.data.Server;
import gg.modl.proto.modl.v1.ServerRole;
import org.jspecify.annotations.Nullable;

public record RealtimePrincipal(
    RealtimeClientKind clientKind,
    Server server,
    @Nullable String email,
    @Nullable String sessionId,
    @Nullable String serverInstanceId,
    @Nullable MinecraftCredential credential,
    ServerRole role,
    @Nullable String serverName,
    @Nullable String pluginVersion
) {
    public static RealtimePrincipal panel(Server server, String email, String sessionId) {
        return new RealtimePrincipal(RealtimeClientKind.PANEL, server, email, sessionId, null, null,
            ServerRole.SERVER_ROLE_UNSPECIFIED, null, null);
    }

    public static RealtimePrincipal minecraft(Server server) {
        return minecraft(server, null);
    }

    public static RealtimePrincipal minecraft(Server server, @Nullable String serverInstanceId) {
        return minecraft(server, serverInstanceId, MinecraftCredential.instanceKey(), ServerRole.SERVER_ROLE_CORE, null, null);
    }

    public static RealtimePrincipal minecraft(Server server, @Nullable String serverInstanceId, MinecraftCredential credential,
                                              ServerRole role, @Nullable String serverName, @Nullable String pluginVersion) {
        return new RealtimePrincipal(RealtimeClientKind.MINECRAFT, server, null, null, serverInstanceId, credential, role,
            serverName, pluginVersion);
    }

    public String serverId() {
        return server.getId();
    }

    public boolean isMinecraft() {
        return clientKind == RealtimeClientKind.MINECRAFT;
    }

    public boolean isNetworkMember() {
        return isMinecraft() && role == ServerRole.SERVER_ROLE_MEMBER;
    }

    public boolean isNetworkCore() {
        return isMinecraft() && role == ServerRole.SERVER_ROLE_CORE;
    }

    public boolean holdsFullCredential() {
        return credential != null && !credential.isMemberOnly();
    }

    @Override
    public String toString() {
        return "RealtimePrincipal[clientKind=" + clientKind + ", serverId=" + server.getId() + ", email=" + email
            + ", serverInstanceId=" + serverInstanceId + ", role=" + role + ", serverName=" + serverName + "]";
    }
}

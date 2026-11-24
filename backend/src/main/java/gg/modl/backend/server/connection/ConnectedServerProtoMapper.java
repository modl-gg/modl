package gg.modl.backend.server.connection;

import gg.modl.backend.infrastructure.proto.ProtoMapperSupport;
import gg.modl.proto.modl.v1.ConnectedServersResponse;
import gg.modl.proto.modl.v1.ServerCompatibility;
import gg.modl.update.VersionCompatibility;
import java.util.List;

final class ConnectedServerProtoMapper {
    private ConnectedServerProtoMapper() {
    }

    static ConnectedServersResponse toResponse(List<ConnectedServer> servers, String modlVersion) {
        ConnectedServersResponse.Builder response = ConnectedServersResponse.newBuilder().setModlVersion(modlVersion);
        servers.forEach(server -> response.addServers(toProto(server)));
        return response.build();
    }

    private static gg.modl.proto.modl.v1.ConnectedServer toProto(ConnectedServer server) {
        gg.modl.proto.modl.v1.ConnectedServer.Builder proto = gg.modl.proto.modl.v1.ConnectedServer.newBuilder()
            .setInstanceId(server.instanceId())
            .setName(server.name())
            .setPlatform(server.platform())
            .setPluginVersion(server.pluginVersion())
            .setServerVersion(server.serverVersion())
            .setOnlinePlayers(server.onlinePlayers())
            .setLastSeen(ProtoMapperSupport.toTimestamp(server.lastSeen().toEpochMilli()))
            .setRole(server.role())
            .setHubConnected(server.hubConnected())
            .setCompatibility(toProto(server.compatibility()));
        if (server.keyName() != null) {
            proto.setKeyName(server.keyName());
        }
        return proto.build();
    }

    private static ServerCompatibility toProto(VersionCompatibility compatibility) {
        return switch (compatibility) {
            case COMPATIBLE -> ServerCompatibility.SERVER_COMPATIBILITY_COMPATIBLE;
            case PLUGIN_OLDER -> ServerCompatibility.SERVER_COMPATIBILITY_PLUGIN_OLDER;
            case SERVER_OLDER -> ServerCompatibility.SERVER_COMPATIBILITY_SERVER_OLDER;
            case UNKNOWN -> ServerCompatibility.SERVER_COMPATIBILITY_UNKNOWN;
        };
    }
}

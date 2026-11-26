package gg.modl.backend.serverkey.controller;

import gg.modl.backend.infrastructure.proto.ProtoMapperSupport;
import gg.modl.backend.serverkey.ServerKey;
import gg.modl.backend.serverkey.ServerKeyScope;
import gg.modl.backend.serverkey.service.CreatedServerKey;
import gg.modl.backend.serverkey.service.ListedProxyServer;
import gg.modl.backend.serverkey.service.ServerKeyService;
import gg.modl.proto.modl.v1.CreateServerKeyResponse;
import gg.modl.proto.modl.v1.ProxiedServer;
import gg.modl.proto.modl.v1.ProxiedServerState;
import gg.modl.proto.modl.v1.ProxiedServerStatus;
import gg.modl.proto.modl.v1.ServerKeysResponse;
import java.util.List;
import java.util.Optional;
import java.util.Set;

final class ServerKeyProtoMapper {
    private ServerKeyProtoMapper() {
    }

    static ServerKeysResponse toResponse(List<ServerKey> keys, Set<String> connectedKeyIds, List<ListedProxyServer> proxyServers) {
        ServerKeysResponse.Builder response = ServerKeysResponse.newBuilder().setLimit(ServerKeyService.LIMIT);
        keys.forEach(key -> response.addKeys(toProto(key, connectedKeyIds.contains(key.id()))));
        proxyServers.forEach(listed -> response.addProxiedServers(toProto(listed)));
        return response.build();
    }

    private static ProxiedServerStatus toProto(ListedProxyServer listed) {
        ProxiedServerStatus.Builder proto = ProxiedServerStatus.newBuilder()
            .setServer(ProxiedServer.newBuilder().setName(listed.server().name()).setAddress(listed.server().address()))
            .setState(toProto(listed.state()));
        if (listed.keyId() != null) {
            proto.setKeyId(listed.keyId());
        }
        return proto.build();
    }

    private static ProxiedServerState toProto(ListedProxyServer.State state) {
        return switch (state) {
            case NO_KEY -> ProxiedServerState.PROXIED_SERVER_STATE_NO_KEY;
            case NOT_CONNECTED -> ProxiedServerState.PROXIED_SERVER_STATE_NOT_CONNECTED;
            case CONNECTED -> ProxiedServerState.PROXIED_SERVER_STATE_CONNECTED;
        };
    }

    static CreateServerKeyResponse toCreated(CreatedServerKey created, String panelUrl) {
        return CreateServerKeyResponse.newBuilder()
            .setKey(toProto(created.key(), false))
            .setSecret(created.secret())
            .setPanelUrl(panelUrl)
            .build();
    }

    static gg.modl.proto.modl.v1.ServerKey toProto(ServerKey key, boolean connected) {
        gg.modl.proto.modl.v1.ServerKey.Builder proto = gg.modl.proto.modl.v1.ServerKey.newBuilder()
            .setId(key.id())
            .setName(key.name())
            .setScope(toProto(key.scope()))
            .setKeyHint(key.keyHint())
            .setCreatedAt(ProtoMapperSupport.toTimestamp(key.createdAt().toEpochMilli()))
            .setConnected(connected)
            .setAllowProxyCommands(key.allowProxyCommands());
        if (key.lastSeenAt() != null) {
            proto.setLastSeenAt(ProtoMapperSupport.toTimestamp(key.lastSeenAt().toEpochMilli()));
        }
        if (key.createdByEmail() != null) {
            proto.setCreatedBy(key.createdByEmail());
        }
        return proto.build();
    }

    static gg.modl.proto.modl.v1.ServerKeyScope toProto(ServerKeyScope scope) {
        return switch (scope) {
            case FULL -> gg.modl.proto.modl.v1.ServerKeyScope.SERVER_KEY_SCOPE_FULL;
            case BEHIND_PROXY -> gg.modl.proto.modl.v1.ServerKeyScope.SERVER_KEY_SCOPE_BEHIND_PROXY;
        };
    }

    static Optional<ServerKeyScope> fromProto(gg.modl.proto.modl.v1.ServerKeyScope scope) {
        return switch (scope) {
            case SERVER_KEY_SCOPE_BEHIND_PROXY -> Optional.of(ServerKeyScope.BEHIND_PROXY);
            case SERVER_KEY_SCOPE_FULL -> Optional.of(ServerKeyScope.FULL);
            case SERVER_KEY_SCOPE_UNSPECIFIED, UNRECOGNIZED -> Optional.empty();
        };
    }
}

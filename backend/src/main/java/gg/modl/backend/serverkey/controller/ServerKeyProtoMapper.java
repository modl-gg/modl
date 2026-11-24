package gg.modl.backend.serverkey.controller;

import gg.modl.backend.infrastructure.proto.ProtoMapperSupport;
import gg.modl.backend.serverkey.ServerKey;
import gg.modl.backend.serverkey.ServerKeyScope;
import gg.modl.backend.serverkey.service.CreatedServerKey;
import gg.modl.backend.serverkey.service.ServerKeyService;
import gg.modl.proto.modl.v1.CreateServerKeyResponse;
import gg.modl.proto.modl.v1.ServerKeysResponse;
import java.util.List;
import java.util.Optional;
import java.util.Set;

final class ServerKeyProtoMapper {
    private ServerKeyProtoMapper() {
    }

    static ServerKeysResponse toResponse(List<ServerKey> keys, Set<String> connectedKeyIds) {
        ServerKeysResponse.Builder response = ServerKeysResponse.newBuilder().setLimit(ServerKeyService.LIMIT);
        keys.forEach(key -> response.addKeys(toProto(key, connectedKeyIds.contains(key.id()))));
        return response.build();
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

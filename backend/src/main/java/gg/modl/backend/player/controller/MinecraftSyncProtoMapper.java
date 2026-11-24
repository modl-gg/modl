package gg.modl.backend.player.controller;

import gg.modl.backend.player.service.MinecraftSyncService;
import gg.modl.backend.server.connection.ServerHeartbeat;
import gg.modl.proto.modl.v1.SyncRequest;
import org.jspecify.annotations.Nullable;
import java.util.List;

final class MinecraftSyncProtoMapper {
    private MinecraftSyncProtoMapper() {
    }

    static List<MinecraftSyncService.OnlinePlayerInput> toOnlinePlayers(SyncRequest request) {
        return request.getOnlinePlayersList().stream()
            .map(player -> new MinecraftSyncService.OnlinePlayerInput(
                player.getUuid(),
                player.getUsername(),
                player.getIpAddress()
            ))
            .toList();
    }

    static ServerHeartbeat toHeartbeat(SyncRequest request) {
        return toHeartbeat(request, null);
    }

    static ServerHeartbeat toHeartbeat(SyncRequest request, @Nullable String pluginVersion) {
        return ServerHeartbeat.reported(
            request.hasServerInstanceId() ? request.getServerInstanceId() : null,
            request.hasServerName() ? request.getServerName() : null,
            request.getOnlinePlayersCount(),
            pluginVersion
        );
    }

    static List<MinecraftSyncService.ChatLogInput> toChatLogs(SyncRequest request) {
        return request.getChatLogsList().stream()
            .map(entry -> new MinecraftSyncService.ChatLogInput(
                entry.getUuid(),
                entry.getUsername(),
                entry.getMessage(),
                entry.getTimestamp(),
                entry.getServer()
            ))
            .toList();
    }

    static List<MinecraftSyncService.CommandLogInput> toCommandLogs(SyncRequest request) {
        return request.getCommandLogsList().stream()
            .map(entry -> new MinecraftSyncService.CommandLogInput(
                entry.getUuid(),
                entry.getUsername(),
                entry.getCommand(),
                entry.getTimestamp(),
                entry.getServer()
            ))
            .toList();
    }
}

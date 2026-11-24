package gg.modl.backend.player.controller;

import gg.modl.backend.player.controller.MinecraftSyncController.ChatLogEntry;
import gg.modl.backend.player.controller.MinecraftSyncController.CommandLogEntry;
import gg.modl.backend.player.controller.MinecraftSyncController.OnlinePlayer;
import gg.modl.backend.player.service.MinecraftSyncService;
import gg.modl.backend.server.connection.ServerHeartbeat;
import org.jspecify.annotations.Nullable;
import java.util.List;

final class SyncRequestMapper {
    private SyncRequestMapper() {
    }

    static List<MinecraftSyncService.OnlinePlayerInput> toOnlinePlayers(@Nullable List<OnlinePlayer> players) {
        if (players == null) {
            return List.of();
        }
        return players.stream()
            .map(player -> new MinecraftSyncService.OnlinePlayerInput(player.uuid(), player.username(), player.ipAddress()))
            .toList();
    }

    static ServerHeartbeat toHeartbeat(MinecraftSyncV2Controller.V2SyncRequest request) {
        return toHeartbeat(request, null);
    }

    static ServerHeartbeat toHeartbeat(MinecraftSyncV2Controller.V2SyncRequest request, @Nullable String pluginVersion) {
        int onlinePlayers = request.onlinePlayers() == null ? 0 : request.onlinePlayers().size();
        return ServerHeartbeat.reported(request.serverInstanceId(), request.serverName(), onlinePlayers, pluginVersion);
    }

    static List<MinecraftSyncService.ChatLogInput> toChatLogs(@Nullable List<ChatLogEntry> entries) {
        if (entries == null) {
            return List.of();
        }
        return entries.stream()
            .map(entry -> new MinecraftSyncService.ChatLogInput(entry.uuid(), entry.username(), entry.message(), entry.timestamp(), entry.server()))
            .toList();
    }

    static List<MinecraftSyncService.CommandLogInput> toCommandLogs(@Nullable List<CommandLogEntry> entries) {
        if (entries == null) {
            return List.of();
        }
        return entries.stream()
            .map(entry -> new MinecraftSyncService.CommandLogInput(entry.uuid(), entry.username(), entry.command(), entry.timestamp(), entry.server()))
            .toList();
    }
}

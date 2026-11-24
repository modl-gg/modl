package gg.modl.minecraft.api.http.request;

import gg.modl.minecraft.api.http.ChatLogEntry;
import gg.modl.minecraft.api.http.CommandLogEntry;
import lombok.Value;
import org.jspecify.annotations.Nullable;

import java.util.List;

@Value
public class SyncRequest {
    String lastSyncTimestamp;
    List<OnlinePlayer> onlinePlayers;
    @Nullable String serverName;
    @Nullable String serverInstanceId;
    @Nullable List<ChatLogEntry> chatLogs;
    @Nullable List<CommandLogEntry> commandLogs;

    @Value
    public static class OnlinePlayer {
        String uuid, username;
        @Nullable String ipAddress;
        long sessionDurationMs;
    }
}

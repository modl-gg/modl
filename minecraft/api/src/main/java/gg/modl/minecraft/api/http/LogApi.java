package gg.modl.minecraft.api.http;

import gg.modl.minecraft.api.http.request.ChatLogBatchRequest;
import gg.modl.minecraft.api.http.request.CommandLogBatchRequest;
import gg.modl.minecraft.api.http.response.ChatLogsResponse;
import gg.modl.minecraft.api.http.response.CommandLogsResponse;

import java.util.concurrent.CompletableFuture;

public interface LogApi {

    CompletableFuture<Void> submitChatLogs(ChatLogBatchRequest request);

    CompletableFuture<Void> submitCommandLogs(CommandLogBatchRequest request);

    CompletableFuture<ChatLogsResponse> getChatLogs(String playerUuid, int limit);

    CompletableFuture<CommandLogsResponse> getCommandLogs(String playerUuid, int limit);
}

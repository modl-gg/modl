package gg.modl.backend.player.controller;

import gg.modl.backend.infrastructure.rest.RESTMappingV2;
import gg.modl.backend.infrastructure.rest.RequestUtil;
import gg.modl.backend.infrastructure.validation.RequestValidationLimits;
import gg.modl.backend.player.controller.MinecraftSyncController.ChatLogEntry;
import gg.modl.backend.player.controller.MinecraftSyncController.CommandLogEntry;
import gg.modl.backend.player.controller.MinecraftSyncController.OnlinePlayer;
import gg.modl.backend.player.dto.response.SyncResult;
import gg.modl.backend.player.service.MinecraftSyncService;
import gg.modl.backend.server.connection.ConnectedServerRegistry;
import gg.modl.backend.server.data.Server;
import gg.modl.update.CompatibilityHeaders;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(RESTMappingV2.MINECRAFT_PLAYERS)
@RequiredArgsConstructor
public class MinecraftSyncV2Controller {
    private final MinecraftSyncService minecraftSyncService;
    private final ConnectedServerRegistry connectedServerRegistry;

    @PostMapping("/sync")
    public ResponseEntity<SyncResult> sync(
        @RequestBody @Valid V2SyncRequest syncRequest,
        HttpServletRequest httpRequest
    ) {
        Server server = RequestUtil.getRequestServer(httpRequest);
        connectedServerRegistry.recordHeartbeat(SyncRequestMapper.toHeartbeat(syncRequest,
            httpRequest.getHeader(CompatibilityHeaders.PLUGIN_VERSION)));
        return ResponseEntity.ok(minecraftSyncService.sync(
            server,
            syncRequest.lastSyncTimestamp(),
            SyncRequestMapper.toOnlinePlayers(syncRequest.onlinePlayers()),
            syncRequest.serverName(),
            SyncRequestMapper.toChatLogs(syncRequest.chatLogs()),
            SyncRequestMapper.toCommandLogs(syncRequest.commandLogs())
        ));
    }

    public record V2SyncRequest(
        @Size(max = RequestValidationLimits.TIMESTAMP_MAX_LENGTH) @Nullable String lastSyncTimestamp,
        @Size(max = RequestValidationLimits.CHAT_LOG_BATCH_MAX_ENTRIES) @Nullable List<@Valid OnlinePlayer> onlinePlayers,
        @Size(max = RequestValidationLimits.LOG_SERVER_NAME_MAX_LENGTH) @Nullable String serverName,
        @Size(max = RequestValidationLimits.CHAT_LOG_BATCH_MAX_ENTRIES) @Nullable List<@Valid ChatLogEntry> chatLogs,
        @Size(max = RequestValidationLimits.CHAT_LOG_BATCH_MAX_ENTRIES) @Nullable List<@Valid CommandLogEntry> commandLogs,
        @Nullable String serverInstanceId
    ) {
    }
}

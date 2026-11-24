package gg.modl.backend.player.controller;

import gg.modl.backend.infrastructure.exception.ValidationException;
import gg.modl.backend.infrastructure.proto.ProtobufMediaTypes;
import gg.modl.backend.infrastructure.rest.RESTMappingV3;
import gg.modl.backend.infrastructure.rest.RequestUtil;
import gg.modl.backend.infrastructure.validation.RequestValidationLimits;
import gg.modl.backend.player.service.MinecraftSyncService;
import gg.modl.backend.player.service.SyncProtoFactory;
import gg.modl.backend.player.dto.response.SyncResult;
import gg.modl.backend.server.connection.ConnectedServerRegistry;
import gg.modl.backend.server.data.Server;
import gg.modl.proto.modl.v1.SimpleResponse;
import gg.modl.proto.modl.v1.SyncRequest;
import gg.modl.proto.modl.v1.SyncResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import gg.modl.update.CompatibilityHeaders;

@RestController
@RequestMapping(RESTMappingV3.PREFIX_MINECRAFT)
@RequiredArgsConstructor
public class MinecraftSyncV3Controller {
    private final MinecraftSyncService minecraftSyncService;
    private final SyncProtoFactory syncProtoFactory;
    private final ConnectedServerRegistry connectedServerRegistry;

    @PostMapping(
        value = "/players/sync",
        consumes = ProtobufMediaTypes.APPLICATION_X_PROTOBUF_VALUE,
        produces = ProtobufMediaTypes.APPLICATION_X_PROTOBUF_VALUE
    )
    public ResponseEntity<SyncResponse> sync(
        @RequestBody @Valid SyncRequest request,
        HttpServletRequest httpRequest
    ) {
        Server server = RequestUtil.getRequestServer(httpRequest);
        requireLogBatchesWithinLimit(request);
        connectedServerRegistry.recordHeartbeat(MinecraftSyncProtoMapper.toHeartbeat(request,
            httpRequest.getHeader(CompatibilityHeaders.PLUGIN_VERSION)));
        SyncResult response = minecraftSyncService.sync(
            server,
            request.getLastSyncTimestamp(),
            MinecraftSyncProtoMapper.toOnlinePlayers(request),
            request.hasServerName() ? request.getServerName() : null,
            MinecraftSyncProtoMapper.toChatLogs(request),
            MinecraftSyncProtoMapper.toCommandLogs(request)
        );

        return ResponseEntity.ok(syncProtoFactory.toSyncResponse(response));
    }

    @PostMapping(
        value = "/presence",
        consumes = ProtobufMediaTypes.APPLICATION_X_PROTOBUF_VALUE,
        produces = ProtobufMediaTypes.APPLICATION_X_PROTOBUF_VALUE
    )
    public ResponseEntity<SimpleResponse> presence(
        @RequestBody @Valid SyncRequest request,
        HttpServletRequest httpRequest
    ) {
        Server server = RequestUtil.getRequestServer(httpRequest);
        connectedServerRegistry.recordHeartbeat(MinecraftSyncProtoMapper.toHeartbeat(request,
            httpRequest.getHeader(CompatibilityHeaders.PLUGIN_VERSION)));
        minecraftSyncService.applyPresence(
            server,
            MinecraftSyncProtoMapper.toOnlinePlayers(request),
            request.hasServerName() ? request.getServerName() : null
        );

        return ResponseEntity.ok(SimpleResponse.newBuilder().setSuccess(true).build());
    }

    @PostMapping(
        value = "/logs",
        consumes = ProtobufMediaTypes.APPLICATION_X_PROTOBUF_VALUE,
        produces = ProtobufMediaTypes.APPLICATION_X_PROTOBUF_VALUE
    )
    public ResponseEntity<SimpleResponse> logs(
        @RequestBody @Valid SyncRequest request,
        HttpServletRequest httpRequest
    ) {
        Server server = RequestUtil.getRequestServer(httpRequest);
        requireLogBatchesWithinLimit(request);
        minecraftSyncService.submitLogs(
            server,
            MinecraftSyncProtoMapper.toChatLogs(request),
            MinecraftSyncProtoMapper.toCommandLogs(request)
        );

        return ResponseEntity.ok(SimpleResponse.newBuilder().setSuccess(true).build());
    }

    private void requireLogBatchesWithinLimit(SyncRequest request) {
        requireBatchWithinLimit(request.getChatLogsCount(), "chatLogs");
        requireBatchWithinLimit(request.getCommandLogsCount(), "commandLogs");
    }

    private void requireBatchWithinLimit(int size, String field) {
        if (size > RequestValidationLimits.CHAT_LOG_BATCH_MAX_ENTRIES) {
            throw new ValidationException(field + " must contain no more than "
                + RequestValidationLimits.CHAT_LOG_BATCH_MAX_ENTRIES + " entries");
        }
    }
}

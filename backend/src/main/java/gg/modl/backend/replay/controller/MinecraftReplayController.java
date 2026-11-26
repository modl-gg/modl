package gg.modl.backend.replay.controller;

import gg.modl.backend.infrastructure.authorization.MemberAccessible;
import gg.modl.backend.infrastructure.exception.ConflictException;
import gg.modl.backend.infrastructure.exception.ResourceNotFoundException;
import gg.modl.backend.infrastructure.exception.ServiceUnavailableException;
import gg.modl.backend.infrastructure.exception.ValidationException;
import gg.modl.backend.infrastructure.rest.RESTMappingV1;
import gg.modl.backend.infrastructure.rest.RequestUtil;
import gg.modl.backend.replay.dto.InitReplayUploadRequest;
import gg.modl.backend.replay.dto.InitReplayUploadResponse;
import gg.modl.backend.replay.service.ReplayConfirmationService;
import gg.modl.backend.replay.service.ReplayService;
import gg.modl.backend.server.data.Server;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.time.Duration;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(RESTMappingV1.MINECRAFT_REPLAYS)
@RequiredArgsConstructor
public class MinecraftReplayController {
    private static final Duration VALIDATION_RETRY_AFTER = Duration.ofSeconds(5);

    private final ReplayService replayService;
    private final ReplayConfirmationService confirmations;

    @PostMapping("/upload")
    @MemberAccessible
    public ResponseEntity<Map<String, Object>> initUpload(
        @RequestBody @Valid InitReplayUploadRequest request,
        HttpServletRequest httpRequest
    ) {
        Server server = RequestUtil.getRequestServer(httpRequest);

        InitReplayUploadResponse response = replayService.initUpload(
            server, request.mcVersion(), request.fileSize(), request.targetUuid(), request.targetName()
        );

        return ResponseEntity.ok(Map.of(
            "status", 200,
            "replayId", response.replayId(),
            "uploadUrl", response.uploadUrl(),
            "method", response.method(),
            "requiredHeaders", response.requiredHeaders()
        ));
    }

    @PostMapping("/confirm/{replayId}")
    @MemberAccessible
    public ResponseEntity<Map<String, Object>> confirmUpload(
        @PathVariable String replayId,
        HttpServletRequest httpRequest
    ) {
        Server server = RequestUtil.getRequestServer(httpRequest);
        return switch (confirmations.confirmUpload(server, replayId)) {
            case CONFIRMED -> ResponseEntity.ok(Map.of(
                "status", 200,
                "success", true,
                "message", "Upload confirmed"
            ));
            case NOT_FOUND -> throw new ResourceNotFoundException("Replay not found", "REPLAY_NOT_FOUND");
            case UPLOAD_MISSING -> throw new ValidationException("The replay upload is missing from storage", "REPLAY_UPLOAD_MISSING");
            case FILE_INVALID -> throw new ValidationException("The uploaded file is not a valid replay", "REPLAY_FILE_INVALID");
            case RESERVATION_LAPSED -> throw new ConflictException("The replay upload reservation expired", "REPLAY_UPLOAD_EXPIRED");
            case ALREADY_FAILED -> throw new ConflictException("The replay upload was already refused", "REPLAY_UPLOAD_FAILED");
            case TRY_AGAIN -> throw new ServiceUnavailableException("The replay cannot be checked right now", "REPLAY_VALIDATION_UNAVAILABLE",
                VALIDATION_RETRY_AFTER);
        };
    }
}

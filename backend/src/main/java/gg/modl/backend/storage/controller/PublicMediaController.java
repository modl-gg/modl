package gg.modl.backend.storage.controller;

import gg.modl.backend.infrastructure.exception.ValidationException;
import gg.modl.backend.infrastructure.rest.RESTMappingV1;
import gg.modl.backend.infrastructure.rest.RequestUtil;
import gg.modl.backend.infrastructure.web.ClientIpResolver;
import gg.modl.backend.server.data.Server;
import gg.modl.backend.storage.ObjectStorage;
import gg.modl.backend.storage.service.MediaAccessService;
import gg.modl.backend.storage.service.MediaValidationService;
import gg.modl.backend.storage.service.StorageKeyUtils;
import gg.modl.backend.storage.service.UploadOrchestrationService;
import gg.modl.proto.modl.v1.ConfirmUploadRequest;
import gg.modl.proto.modl.v1.MediaConfigResponse;
import gg.modl.proto.modl.v1.PresignUploadRequest;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(RESTMappingV1.PUBLIC_MEDIA)
@RequiredArgsConstructor
public class PublicMediaController {
    private final ObjectStorage objectStorage;
    private final MediaValidationService validationService;
    private final MediaAccessService mediaAccessService;
    private final UploadOrchestrationService uploadOrchestrationService;
    private final ClientIpResolver clientIpResolver;

    private static final Set<String> PUBLIC_ALLOWED_UPLOAD_TYPES = Set.of("ticket", "tickets", "appeal");
    private static final String TEMP_LIMIT_MESSAGE =
        "Temporary upload limit reached. Attach your files to a ticket or appeal, or try again later.";

    @GetMapping("/config")
    public ResponseEntity<MediaConfigResponse> getMediaConfig() {
        return ResponseEntity.ok(StorageProtoMapper.toMediaConfigResponse(
            validationService.getAllSupportedTypes(),
            validationService.getAllSizeLimits(),
            objectStorage.publicBaseUrl()
        ));
    }

    @PostMapping("/presign")
    public ResponseEntity<?> getPresignedUploadUrl(
        @RequestBody PresignUploadRequest presignRequest,
        HttpServletRequest request
    ) {
        Server server = RequestUtil.getRequestServer(request);
        String uploadType = presignRequest.getUploadType();
        String entityId = presignRequest.hasEntityId() ? presignRequest.getEntityId() : null;
        String normalizedEntityId = entityId != null ? entityId.trim() : null;
        String accessToken = presignRequest.hasAccessToken() ? presignRequest.getAccessToken() : null;

        if (!PUBLIC_ALLOWED_UPLOAD_TYPES.contains(uploadType)) {
            return ResponseEntity.badRequest().body(Map.of(
                "error", "Upload type not allowed for public uploads. Allowed: " + PUBLIC_ALLOWED_UPLOAD_TYPES
            ));
        }

        MediaAccessService.AccessResult accessResult = mediaAccessService.validatePublicUploadAccess(
            server,
            uploadType,
            normalizedEntityId,
            accessToken
        );
        if (!accessResult.isAllowed()) {
            return toResponse(accessResult);
        }

        UploadOrchestrationService.PresignOutcome outcome = uploadOrchestrationService.presign(server,
            new UploadOrchestrationService.UploadPresignRequest(
                StorageKeyUtils.normalizeUploadType(uploadType),
                presignRequest.getFileName(),
                presignRequest.getContentType(),
                presignRequest.getFileSize(),
                normalizedEntityId,
                clientIpResolver.resolveNetwork(request)
            ));

        return switch (outcome.status()) {
            case SUCCESS -> ResponseEntity.ok(StorageProtoMapper.toPresignUploadResponse(outcome.requireUpload()));
            case VALIDATION_FAILED -> ResponseEntity.badRequest().body(Map.of("error", outcome.requireMessage()));
            case STORAGE_FULL -> ResponseEntity.status(HttpStatus.INSUFFICIENT_STORAGE).body(Map.of("error", outcome.requireMessage()));
            case TEMP_LIMIT_EXCEEDED -> throw new ValidationException(TEMP_LIMIT_MESSAGE);
        };
    }

    @PostMapping("/confirm")
    public ResponseEntity<?> confirmUpload(
        @RequestBody ConfirmUploadRequest confirmRequest,
        HttpServletRequest request
    ) {
        Server server = RequestUtil.getRequestServer(request);
        String key = confirmRequest.getKey();
        validationService.assertKeyOwnedByServer(server, key);

        String uploadType = validationService.extractUploadType(key);
        String entityId = validationService.extractEntityId(key);
        if (!PUBLIC_ALLOWED_UPLOAD_TYPES.contains(uploadType)) {
            return ResponseEntity.status(403).body(Map.of(
                "error", "Upload type not allowed for public confirmation"
            ));
        }
        if (entityId == null || entityId.isBlank()) {
            throw new ValidationException("Invalid upload key");
        }

        MediaAccessService.AccessResult accessResult = mediaAccessService.validatePublicUploadAccess(
            server,
            uploadType,
            entityId,
            confirmRequest.hasAccessToken() ? confirmRequest.getAccessToken() : null
        );
        if (!accessResult.isAllowed()) {
            return toResponse(accessResult);
        }

        UploadOrchestrationService.ConfirmOutcome outcome = uploadOrchestrationService.confirm(server, key);

        return switch (outcome.status()) {
            case SUCCESS -> ResponseEntity.ok(StorageProtoMapper.toUploadResponse(outcome.requireUpload()));
            case UPLOAD_NOT_FOUND -> ResponseEntity.badRequest().body(Map.of(
                "error", "Upload not found",
                "message", "The file was not uploaded or the upload link expired"
            ));
        };
    }

    private ResponseEntity<?> toResponse(MediaAccessService.AccessResult result) {
        return switch (result.status()) {
            case NOT_FOUND -> ResponseEntity.notFound().build();
            case DENIED -> ResponseEntity.status(403).body(Map.of("error", result.requireError()));
            case ALLOWED -> throw new IllegalStateException("Should not convert an allowed result to a response");
        };
    }
}

package gg.modl.backend.storage.controller;

import gg.modl.backend.infrastructure.authorization.PanelAccessRule;
import gg.modl.backend.infrastructure.exception.ForbiddenException;
import gg.modl.backend.infrastructure.authorization.RequiresPanelPermission;
import gg.modl.backend.infrastructure.rest.RESTMappingV1;
import gg.modl.backend.infrastructure.rest.RequestUtil;
import gg.modl.backend.role.service.PermissionService;
import gg.modl.backend.server.data.Server;
import gg.modl.backend.storage.MediaCategory;
import gg.modl.backend.storage.ObjectStorage;
import gg.modl.backend.storage.service.MediaValidationService;
import gg.modl.backend.storage.service.PanelMediaUploadService;
import gg.modl.backend.storage.service.StorageDeletionService;
import gg.modl.backend.storage.service.StorageKeyUtils;
import gg.modl.backend.storage.service.UploadOrchestrationService;
import gg.modl.proto.modl.v1.ConfirmUploadRequest;
import gg.modl.proto.modl.v1.MediaConfigResponse;
import gg.modl.proto.modl.v1.PresignUploadRequest;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(RESTMappingV1.PANEL_MEDIA)
@RequiresPanelPermission(view = PermissionService.ADMIN_SETTINGS_VIEW_CONTENT, modify = PermissionService.ADMIN_SETTINGS_MODIFY_CONTENT)
@RequiredArgsConstructor
public class PanelMediaController {
    private final ObjectStorage objectStorage;
    private final MediaValidationService validationService;
    private final StorageDeletionService storageDeletionService;
    private final PanelMediaUploadService panelMediaUploadService;

    @GetMapping("/config")
    @RequiresPanelPermission(rule = PanelAccessRule.PERMIT_ALL)
    public ResponseEntity<MediaConfigResponse> getMediaConfig() {
        return ResponseEntity.ok(StorageProtoMapper.toMediaConfigResponse(
            validationService.getAllSupportedTypes(),
            validationService.getAllSizeLimits(),
            objectStorage.publicBaseUrl()
        ));
    }

    @DeleteMapping("/{*key}")
    public ResponseEntity<?> deleteFile(
        @PathVariable String key,
        HttpServletRequest request
    ) {
        Server server = RequestUtil.getRequestServer(request);
        String normalizedKey = StorageKeyUtils.stripLeadingSlash(key);
        validationService.assertKeyOwnedByServer(server, normalizedKey);

        return switch (storageDeletionService.deleteRecordedMedia(server, normalizedKey, MediaCategory.ARTICLE)) {
            case DELETED -> ResponseEntity.ok(Map.of("message", "File deleted"));
            case NOT_FOUND -> ResponseEntity.notFound().build();
            case WRONG_CATEGORY -> throw new ForbiddenException("Only knowledgebase media can be deleted here");
        };
    }

    @PostMapping("/presign")
    @RequiresPanelPermission(rule = PanelAccessRule.PERMIT_ALL)
    public ResponseEntity<?> getPresignedUploadUrl(
        @RequestBody PresignUploadRequest presignRequest,
        HttpServletRequest request
    ) {
        Server server = RequestUtil.getRequestServer(request);
        UploadOrchestrationService.PresignOutcome outcome = panelMediaUploadService.presign(server,
            RequestUtil.requireSessionEmail(request),
            new UploadOrchestrationService.UploadPresignRequest(
                presignRequest.getUploadType(),
                presignRequest.getFileName(),
                presignRequest.getContentType(),
                presignRequest.getFileSize(),
                presignRequest.hasEntityId() ? presignRequest.getEntityId() : null,
                null
            ));

        return switch (outcome.status()) {
            case SUCCESS -> ResponseEntity.ok(StorageProtoMapper.toPresignUploadResponse(outcome.requireUpload()));
            case STORAGE_FULL -> ResponseEntity.status(HttpStatus.INSUFFICIENT_STORAGE).body(Map.of("error", outcome.requireMessage()));
            case VALIDATION_FAILED, TEMP_LIMIT_EXCEEDED ->
                ResponseEntity.badRequest().body(Map.of("error", outcome.requireMessage()));
        };
    }

    @PostMapping("/confirm")
    @RequiresPanelPermission(rule = PanelAccessRule.PERMIT_ALL)
    public ResponseEntity<?> confirmUpload(
        @RequestBody ConfirmUploadRequest confirmRequest,
        HttpServletRequest request
    ) {
        Server server = RequestUtil.getRequestServer(request);
        UploadOrchestrationService.ConfirmOutcome outcome =
            panelMediaUploadService.confirm(server, RequestUtil.requireSessionEmail(request), confirmRequest.getKey());

        return switch (outcome.status()) {
            case SUCCESS -> ResponseEntity.ok(StorageProtoMapper.toUploadResponse(outcome.requireUpload()));
            case UPLOAD_NOT_FOUND -> ResponseEntity.badRequest().body(Map.of(
                "error", "Upload not found",
                "message", "The file was not uploaded or the upload link expired"
            ));
        };
    }
}

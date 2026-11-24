package gg.modl.backend.storage.settings;

import gg.modl.backend.infrastructure.authorization.PanelAccessRule;
import gg.modl.backend.infrastructure.authorization.RequiresPanelPermission;
import gg.modl.backend.infrastructure.exception.ValidationException;
import gg.modl.backend.infrastructure.rest.RESTMappingV1;
import gg.modl.backend.infrastructure.rest.RequestUtil;
import gg.modl.backend.limits.OperatorLimitGroup;
import gg.modl.backend.limits.OperatorLimits;
import gg.modl.backend.limits.controller.OperatorLimitProtoMapper;
import gg.modl.backend.role.service.PermissionService;
import gg.modl.backend.server.data.Server;
import gg.modl.backend.settings.controller.ChangesNoSettings;
import gg.modl.backend.storage.StorageType;
import gg.modl.backend.storage.backend.StorageBackendService;
import gg.modl.backend.storage.backend.StorageBackendView;
import gg.modl.proto.modl.v1.PatchOperatorLimitsRequest;
import gg.modl.proto.modl.v1.SaveS3ConnectionRequest;
import gg.modl.proto.modl.v1.StorageSettingsResponse;
import gg.modl.proto.modl.v1.SwitchStorageBackendRequest;
import gg.modl.proto.modl.v1.TestS3ConnectionRequest;
import gg.modl.proto.modl.v1.TestS3ConnectionResponse;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(RESTMappingV1.PANEL_SETTINGS_STORAGE)
@RequiresPanelPermission(view = PermissionService.ADMIN_SETTINGS_VIEW_STORAGE, modify = PermissionService.ADMIN_SETTINGS_MODIFY_STORAGE)
@RequiredArgsConstructor
public class PanelStorageSettingsController {
    private final OperatorLimits limits;
    private final StorageBackendService backends;
    private final StorageUsageReport usageReport;

    @GetMapping
    public StorageSettingsResponse get() {
        return response(backends.view());
    }

    @PatchMapping("/limits")
    public StorageSettingsResponse patchLimits(@RequestBody PatchOperatorLimitsRequest body, HttpServletRequest request) {
        limits.change(server(request), RequestUtil.requireSessionEmail(request), body.getExpectedVersion(), OperatorLimitGroup.STORAGE,
            OperatorLimitProtoMapper.toChanges(body.getLimitsList()));
        return response(backends.view());
    }

    @PutMapping("/s3")
    @RequiresPanelPermission(rule = PanelAccessRule.SUPER_ADMIN)
    public StorageSettingsResponse saveS3(@RequestBody SaveS3ConnectionRequest body, HttpServletRequest request) {
        return response(backends.saveS3(server(request), RequestUtil.requireSessionEmail(request),
            StorageSettingsProtoMapper.toS3(body.getConnection())));
    }

    @PostMapping("/s3/test")
    @ChangesNoSettings
    @RequiresPanelPermission(rule = PanelAccessRule.SUPER_ADMIN)
    public TestS3ConnectionResponse testS3(@RequestBody TestS3ConnectionRequest body) {
        return StorageSettingsProtoMapper.toResponse(backends.test(StorageSettingsProtoMapper.toS3(body.getConnection())));
    }

    @PostMapping("/switch")
    public StorageSettingsResponse switchBackend(@RequestBody SwitchStorageBackendRequest body, HttpServletRequest request) {
        StorageType target = StorageSettingsProtoMapper.toStorageType(body.getTarget())
            .orElseThrow(() -> new ValidationException("Choose local or S3 storage"));
        return response(backends.switchTo(server(request), RequestUtil.requireSessionEmail(request), target));
    }

    @PostMapping("/transfer/retry")
    public StorageSettingsResponse retry(HttpServletRequest request) {
        return response(backends.retry(server(request), RequestUtil.requireSessionEmail(request)));
    }

    @PostMapping("/old-copies/remove")
    @RequiresPanelPermission(rule = PanelAccessRule.SUPER_ADMIN)
    public StorageSettingsResponse removeOldCopies(HttpServletRequest request) {
        return response(backends.removeOldCopies(server(request), RequestUtil.requireSessionEmail(request)));
    }

    private StorageSettingsResponse response(StorageBackendView backend) {
        return StorageSettingsProtoMapper.toResponse(limits.all(OperatorLimitGroup.STORAGE), limits.version(), backend,
            usageReport.current(backend.active()));
    }

    private static Server server(HttpServletRequest request) {
        return RequestUtil.getRequestServer(request);
    }
}

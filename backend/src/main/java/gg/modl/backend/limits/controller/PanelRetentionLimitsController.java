package gg.modl.backend.limits.controller;

import gg.modl.backend.infrastructure.authorization.PanelAccessRule;
import gg.modl.backend.infrastructure.authorization.RequiresPanelPermission;
import gg.modl.backend.infrastructure.rest.RESTMappingV1;
import gg.modl.backend.infrastructure.rest.RequestUtil;
import gg.modl.backend.limits.OperatorLimitGroup;
import gg.modl.backend.limits.OperatorLimits;
import gg.modl.backend.role.service.PermissionService;
import gg.modl.proto.modl.v1.OperatorLimitsResponse;
import gg.modl.proto.modl.v1.PatchOperatorLimitsRequest;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(RESTMappingV1.PANEL_SETTINGS_RETENTION_LIMITS)
@RequiresPanelPermission(view = PermissionService.ADMIN_SETTINGS_VIEW, modify = PermissionService.ADMIN_SETTINGS_MODIFY)
@RequiredArgsConstructor
public class PanelRetentionLimitsController {
    private final OperatorLimits limits;

    @GetMapping
    public OperatorLimitsResponse get() {
        return response();
    }

    @PatchMapping
    @RequiresPanelPermission(rule = PanelAccessRule.SUPER_ADMIN)
    public OperatorLimitsResponse patch(@RequestBody PatchOperatorLimitsRequest body, HttpServletRequest request) {
        limits.change(RequestUtil.getRequestServer(request), RequestUtil.requireSessionEmail(request), body.getExpectedVersion(),
            OperatorLimitGroup.RETENTION, OperatorLimitProtoMapper.toChanges(body.getLimitsList()));
        return response();
    }

    private OperatorLimitsResponse response() {
        return OperatorLimitProtoMapper.toResponse(limits.all(OperatorLimitGroup.RETENTION), limits.version());
    }
}

package gg.modl.backend.replay.controller;

import gg.modl.backend.infrastructure.authorization.RequiresPanelPermission;
import gg.modl.backend.infrastructure.exception.ValidationException;
import gg.modl.backend.infrastructure.rest.RESTMappingV1;
import gg.modl.backend.infrastructure.rest.RequestUtil;
import gg.modl.backend.replay.settings.ReplaySettingsService;
import gg.modl.backend.role.service.PermissionService;
import gg.modl.proto.modl.v1.ReplaySettingsEnvelope;
import gg.modl.proto.modl.v1.UpdateReplaySettingsRequest;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(RESTMappingV1.PANEL_SETTINGS_REPLAYS)
@RequiresPanelPermission(view = PermissionService.ADMIN_SETTINGS_VIEW, modify = PermissionService.ADMIN_SETTINGS_MODIFY)
@RequiredArgsConstructor
public class PanelReplaySettingsController {
    private final ReplaySettingsService settingsService;

    @GetMapping
    public ReplaySettingsEnvelope getSettings() {
        return ReplaySettingsProtoMapper.toEnvelope(settingsService.view());
    }

    @PutMapping
    public ReplaySettingsEnvelope updateSettings(@RequestBody UpdateReplaySettingsRequest body, HttpServletRequest request) {
        if (!body.hasSettings()) {
            throw new ValidationException("The replay settings are required");
        }
        return ReplaySettingsProtoMapper.toEnvelope(settingsService.update(RequestUtil.getRequestServer(request),
            RequestUtil.requireSessionEmail(request), body.getExpectedVersion(),
            ReplaySettingsProtoMapper.fromProto(body.getSettings())));
    }
}

package gg.modl.backend.settings.controller;

import gg.modl.backend.ai.connection.AiConnectionService;
import gg.modl.backend.infrastructure.authorization.RequiresPanelPermission;
import gg.modl.backend.infrastructure.rest.RESTMappingV1;
import gg.modl.backend.role.service.PermissionService;
import gg.modl.proto.modl.v1.AiStatusResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(RESTMappingV1.PANEL_SETTINGS + "/ai/status")
@RequiresPanelPermission(view = PermissionService.ADMIN_SETTINGS_VIEW)
@RequiredArgsConstructor
public class PanelAiStatusController {
    private final AiConnectionService connectionService;

    @GetMapping
    public AiStatusResponse getStatus() {
        return AiSettingsProtoMapper.toResponse(connectionService.status());
    }
}

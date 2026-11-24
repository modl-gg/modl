package gg.modl.backend.databasemove.controller;

import gg.modl.backend.databasemove.service.DatabaseMoveService;
import gg.modl.backend.infrastructure.authorization.PanelAccessRule;
import gg.modl.backend.infrastructure.authorization.RequiresPanelPermission;
import gg.modl.backend.infrastructure.rest.RESTMappingV1;
import gg.modl.backend.infrastructure.rest.RequestUtil;
import gg.modl.backend.settings.controller.ChangesNoSettings;
import gg.modl.proto.modl.v1.DatabaseSettingsResponse;
import gg.modl.proto.modl.v1.MoveDatabaseRequest;
import gg.modl.proto.modl.v1.TestDatabaseConnectionRequest;
import gg.modl.proto.modl.v1.TestDatabaseConnectionResponse;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(RESTMappingV1.PANEL_SETTINGS_DATABASE)
@RequiresPanelPermission(rule = PanelAccessRule.SUPER_ADMIN)
@RequiredArgsConstructor
public class PanelDatabaseSettingsController {
    private final DatabaseMoveService moves;

    @GetMapping
    public DatabaseSettingsResponse get() {
        return DatabaseSettingsProtoMapper.toResponse(moves.view());
    }

    @PostMapping("/test")
    @ChangesNoSettings
    public TestDatabaseConnectionResponse test(@RequestBody TestDatabaseConnectionRequest body) {
        return DatabaseSettingsProtoMapper.toResponse(moves.test(DatabaseSettingsProtoMapper.toConnection(body.getConnection())));
    }

    @PostMapping("/move")
    public DatabaseSettingsResponse move(@RequestBody MoveDatabaseRequest body, HttpServletRequest request) {
        return DatabaseSettingsProtoMapper.toResponse(moves.move(RequestUtil.getRequestServer(request),
            RequestUtil.requireSessionEmail(request), DatabaseSettingsProtoMapper.toConnection(body.getConnection()),
            body.getManualRestartAcknowledged()));
    }
}

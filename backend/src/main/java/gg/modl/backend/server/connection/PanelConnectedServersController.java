package gg.modl.backend.server.connection;

import gg.modl.backend.infrastructure.authorization.RequiresPanelPermission;
import gg.modl.backend.infrastructure.rest.RESTMappingV1;
import gg.modl.backend.role.service.PermissionService;
import gg.modl.proto.modl.v1.ConnectedServersResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(RESTMappingV1.PANEL_SERVERS)
@RequiredArgsConstructor
public class PanelConnectedServersController {
    private final ConnectedServersView connectedServers;

    @GetMapping("/connected")
    @RequiresPanelPermission(PermissionService.ADMIN_SETTINGS_VIEW)
    public ConnectedServersResponse connected() {
        return ConnectedServerProtoMapper.toResponse(connectedServers.connected(), connectedServers.modlVersion());
    }
}

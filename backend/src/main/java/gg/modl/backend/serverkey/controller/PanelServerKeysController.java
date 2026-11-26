package gg.modl.backend.serverkey.controller;

import gg.modl.backend.infrastructure.authorization.PanelAccessRule;
import gg.modl.backend.infrastructure.authorization.RequiresPanelPermission;
import gg.modl.backend.infrastructure.exception.ValidationException;
import gg.modl.backend.infrastructure.rest.RESTMappingV1;
import gg.modl.backend.infrastructure.rest.RequestUtil;
import gg.modl.backend.instance.PublicUrls;
import gg.modl.backend.network.LiveNetworkView;
import gg.modl.backend.serverkey.ServerKey;
import gg.modl.backend.serverkey.ServerKeyScope;
import gg.modl.backend.serverkey.service.CreatedServerKey;
import gg.modl.backend.serverkey.service.ProxyServerListing;
import gg.modl.backend.serverkey.service.ServerKeyService;
import gg.modl.proto.modl.v1.CreateServerKeyRequest;
import gg.modl.proto.modl.v1.CreateServerKeyResponse;
import gg.modl.proto.modl.v1.ServerKeysResponse;
import gg.modl.proto.modl.v1.UpdateServerKeyRequest;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(RESTMappingV1.PANEL_SETTINGS_SERVER_KEYS)
@RequiredArgsConstructor
public class PanelServerKeysController {
    private final ServerKeyService serverKeyService;
    private final LiveNetworkView liveNetwork;
    private final ProxyServerListing proxyServers;
    private final PublicUrls publicUrls;

    @GetMapping
    @RequiresPanelPermission(rule = PanelAccessRule.SUPER_ADMIN)
    public ServerKeysResponse list() {
        List<ServerKey> keys = serverKeyService.list();
        Set<String> connectedKeyIds = liveNetwork.connectedKeyIds();
        return ServerKeyProtoMapper.toResponse(keys, connectedKeyIds, proxyServers.list(keys, connectedKeyIds));
    }

    @PostMapping
    @RequiresPanelPermission(rule = PanelAccessRule.SUPER_ADMIN)
    public CreateServerKeyResponse create(@RequestBody CreateServerKeyRequest request, HttpServletRequest httpRequest) {
        CreatedServerKey created = serverKeyService.create(RequestUtil.getRequestServer(httpRequest), request.getName(),
            scopeOf(request), request.getAllowProxyCommands(), RequestUtil.requireSessionEmail(httpRequest));
        return ServerKeyProtoMapper.toCreated(created, publicUrls.panel());
    }

    @PatchMapping("/{id}")
    @RequiresPanelPermission(rule = PanelAccessRule.SUPER_ADMIN)
    public gg.modl.proto.modl.v1.ServerKey update(@PathVariable String id, @RequestBody UpdateServerKeyRequest request,
                                                  HttpServletRequest httpRequest) {
        ServerKey updated = serverKeyService.setAllowProxyCommands(RequestUtil.getRequestServer(httpRequest), id,
            request.getAllowProxyCommands(), RequestUtil.requireSessionEmail(httpRequest));
        return ServerKeyProtoMapper.toProto(updated, liveNetwork.connectedKeyIds().contains(updated.id()));
    }

    @DeleteMapping("/{id}")
    @RequiresPanelPermission(rule = PanelAccessRule.SUPER_ADMIN)
    public ResponseEntity<Void> revoke(@PathVariable String id, HttpServletRequest httpRequest) {
        serverKeyService.revoke(RequestUtil.getRequestServer(httpRequest), id, RequestUtil.requireSessionEmail(httpRequest));
        return ResponseEntity.noContent().build();
    }

    private static ServerKeyScope scopeOf(CreateServerKeyRequest request) {
        return ServerKeyProtoMapper.fromProto(request.getScope()).orElseThrow(() ->
            new ValidationException("Choose whether the server runs behind a proxy", "INVALID_ARGUMENT"));
    }
}

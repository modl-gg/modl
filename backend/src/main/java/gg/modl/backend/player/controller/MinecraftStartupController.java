package gg.modl.backend.player.controller;

import gg.modl.backend.infrastructure.authorization.MemberAccessible;
import gg.modl.backend.infrastructure.rest.RESTMappingV2;
import gg.modl.backend.infrastructure.rest.RequestAttribute;
import gg.modl.backend.instance.MinecraftCredential;
import gg.modl.backend.network.NetworkRolePolicy;
import gg.modl.backend.player.service.MinecraftStartupService;
import gg.modl.backend.server.connection.ServerStartupReport;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(RESTMappingV2.PREFIX_MINECRAFT)
@RequiredArgsConstructor
public class MinecraftStartupController {
    private final MinecraftStartupService minecraftStartupService;

    @PostMapping("/startup")
    @MemberAccessible
    public ResponseEntity<Map<String, Object>> startup(@RequestBody(required = false) @Nullable StartupRequest body,
                                                       HttpServletRequest httpRequest) {
        StartupRequest request = body == null ? StartupRequest.EMPTY : body;
        MinecraftCredential credential = httpRequest.getAttribute(RequestAttribute.MINECRAFT_CREDENTIAL) instanceof MinecraftCredential presented
            ? presented
            : MinecraftCredential.instanceKey();
        return ResponseEntity.ok(minecraftStartupService.handleStartup(credential, ServerStartupReport.of(
            request.pluginVersion(), request.platformType(), request.serverVersion(), NetworkRolePolicy.requestedRole(request.serverRole()),
            credential.keyName())));
    }

    public record StartupRequest(@Nullable String pluginVersion, @Nullable String platformType, @Nullable String serverVersion,
                                 @Nullable String serverRole) {
        static final StartupRequest EMPTY = new StartupRequest(null, null, null, null);
    }
}

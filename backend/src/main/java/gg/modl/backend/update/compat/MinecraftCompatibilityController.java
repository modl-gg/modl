package gg.modl.backend.update.compat;

import gg.modl.backend.infrastructure.authorization.MemberAccessible;
import gg.modl.backend.infrastructure.rest.RESTMappingV2;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class MinecraftCompatibilityController {
    private final PluginCompatibilityPolicy policy;

    @MemberAccessible
    @GetMapping(RESTMappingV2.MINECRAFT_COMPATIBILITY)
    public ResponseEntity<Void> compatibility(HttpServletRequest request) {
        PluginIdentity identity = PluginIdentity.fromHeaders(request);
        policy.requireCompatible(identity == null ? PluginIdentity.of("", null, null) : identity);
        return ResponseEntity.noContent().build();
    }
}

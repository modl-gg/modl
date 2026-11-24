package gg.modl.backend.settings.controller;

import gg.modl.backend.infrastructure.authorization.PanelAccessRule;
import gg.modl.backend.infrastructure.authorization.RequiresPanelPermission;
import gg.modl.backend.infrastructure.rest.RESTMappingV1;
import gg.modl.backend.infrastructure.rest.RequestUtil;
import gg.modl.backend.server.data.Server;
import gg.modl.backend.settings.service.ApiKeySettingsService;
import gg.modl.proto.modl.v1.ApiKeyExistsResponse;
import gg.modl.proto.modl.v1.ApiKeyGenerateResponse;
import gg.modl.proto.modl.v1.ApiKeyRevealResponse;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(RESTMappingV1.PANEL_SETTINGS + "/api-keys/panel")
@RequiresPanelPermission(rule = PanelAccessRule.SUPER_ADMIN)
@RequiredArgsConstructor
public class PanelApiKeyController {
    private final ApiKeySettingsService apiKeySettingsService;

    @PostMapping("/generate")
    public ApiKeyGenerateResponse generateApiKey(HttpServletRequest request) {
        Server server = RequestUtil.getRequestServer(request);
        String apiKey = apiKeySettingsService.generateApiKey(server);
        return PanelSettingsProtoMapper.toApiKeyGenerateResponse("API key generated successfully", apiKey);
    }

    @GetMapping("/reveal")
    public ResponseEntity<ApiKeyRevealResponse> revealApiKey(HttpServletRequest request) {
        String apiKey = apiKeySettingsService.revealApiKey(RequestUtil.getRequestServer(request));
        if (apiKey == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(PanelSettingsProtoMapper.toApiKeyRevealResponse(apiKey));
    }

    @GetMapping("/exists")
    public ApiKeyExistsResponse checkApiKeyExists(HttpServletRequest request) {
        Server server = RequestUtil.getRequestServer(request);
        return PanelSettingsProtoMapper.toApiKeyExistsResponse(
            apiKeySettingsService.hasApiKey(server), apiKeySettingsService.isManagedByConfig());
    }
}

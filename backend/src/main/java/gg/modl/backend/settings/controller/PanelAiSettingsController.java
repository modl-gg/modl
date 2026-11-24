package gg.modl.backend.settings.controller;

import gg.modl.backend.ai.catalog.AiModelCatalogService;
import gg.modl.backend.ai.catalog.AiModelRetirementCheck;
import gg.modl.backend.ai.connection.AiConnectionService;
import gg.modl.backend.ai.connection.AiSettingsView;
import gg.modl.backend.ai.model.AiConnection;
import gg.modl.backend.ai.model.AiRuntime;
import gg.modl.backend.ai.probe.AiProbeResult;
import gg.modl.backend.ai.probe.AiProbeService;
import gg.modl.backend.infrastructure.authorization.PanelAccessRule;
import gg.modl.backend.infrastructure.authorization.RequiresPanelPermission;
import gg.modl.backend.infrastructure.rest.RESTMappingV1;
import gg.modl.backend.infrastructure.rest.RequestUtil;
import gg.modl.proto.modl.v1.AiModelsResponse;
import gg.modl.proto.modl.v1.AiSettingsResponse;
import gg.modl.proto.modl.v1.AiTestResponse;
import gg.modl.proto.modl.v1.ListAiModelsRequest;
import gg.modl.proto.modl.v1.SaveAiConnectionRequest;
import gg.modl.proto.modl.v1.TestAiConnectionRequest;
import gg.modl.proto.modl.v1.UpdateAiSettingsRequest;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(RESTMappingV1.PANEL_SETTINGS + "/ai")
@RequiresPanelPermission(rule = PanelAccessRule.SUPER_ADMIN)
@RequiredArgsConstructor
public class PanelAiSettingsController {
    private final AiConnectionService connectionService;
    private final AiModelCatalogService modelCatalog;
    private final AiProbeService probeService;
    private final AiRuntime runtime;
    private final AiModelRetirementCheck retirementCheck;

    @GetMapping
    public AiSettingsResponse getSettings() {
        return toResponse(connectionService.view());
    }

    @PutMapping
    public AiSettingsResponse updateSettings(@RequestBody UpdateAiSettingsRequest body, HttpServletRequest request) {
        AiSettingsView view = connectionService.updateSettings(RequestUtil.requireSessionEmail(request),
            body.hasActiveProvider() ? body.getActiveProvider() : null,
            body.hasLimitsUpdate() ? AiSettingsProtoMapper.toLimitsUpdate(body.getLimitsUpdate()) : null);
        return toResponse(view);
    }

    @PutMapping("/connections/{provider}")
    public AiSettingsResponse saveConnection(@PathVariable String provider, @RequestBody SaveAiConnectionRequest body,
                                             HttpServletRequest request) {
        return toResponse(connectionService.saveConnection(RequestUtil.requireSessionEmail(request), provider,
            AiSettingsProtoMapper.toInput(body)));
    }

    @DeleteMapping("/connections/{provider}")
    public AiSettingsResponse deleteConnection(@PathVariable String provider, HttpServletRequest request) {
        return toResponse(connectionService.deleteConnection(RequestUtil.requireSessionEmail(request), provider));
    }

    @PostMapping("/models")
    public AiModelsResponse listModels(@RequestBody ListAiModelsRequest body) {
        AiConnection connection = connectionService.draft(AiSettingsProtoMapper.toDraft(body.getConnection()), false);
        return AiSettingsProtoMapper.toResponse(modelCatalog.list(connection, body.getRefresh()));
    }

    @PostMapping("/test")
    public AiTestResponse testConnection(@RequestBody TestAiConnectionRequest body) {
        AiConnection connection = connectionService.draft(AiSettingsProtoMapper.toDraft(body.getConnection()), true);
        AiProbeResult result = probeService.test(connection);
        return AiSettingsProtoMapper.toResponse(result);
    }

    private AiSettingsResponse toResponse(AiSettingsView view) {
        return AiSettingsProtoMapper.toResponse(view, runtime.view(), retirementCheck.notice());
    }
}

package gg.modl.backend.update.api;

import gg.modl.backend.infrastructure.authorization.PanelAccessRule;
import gg.modl.backend.infrastructure.authorization.RequiresPanelPermission;
import gg.modl.backend.infrastructure.rest.RESTMappingV1;
import gg.modl.backend.infrastructure.rest.RequestUtil;
import gg.modl.proto.modl.v1.DismissUpdateRequest;
import gg.modl.proto.modl.v1.InstallUpdateRequest;
import gg.modl.proto.modl.v1.UpdateSettingsRequest;
import gg.modl.proto.modl.v1.UpdateStatusResponse;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(RESTMappingV1.PANEL_UPDATES)
@RequiresPanelPermission(rule = PanelAccessRule.SUPER_ADMIN)
@RequiredArgsConstructor
public class PanelUpdatesController {
    private final UpdateStatusService updates;

    @GetMapping
    public UpdateStatusResponse status() {
        return UpdateProtoMapper.toResponse(updates.status());
    }

    @PostMapping("/check")
    public UpdateStatusResponse check() {
        return UpdateProtoMapper.toResponse(updates.checkNow());
    }

    @PostMapping("/dismiss")
    public UpdateStatusResponse dismiss(@RequestBody DismissUpdateRequest body, HttpServletRequest request) {
        return UpdateProtoMapper.toResponse(updates.dismiss(body.getVersion(), RequestUtil.requireSessionEmail(request)));
    }

    @PutMapping("/settings")
    public UpdateStatusResponse settings(@RequestBody UpdateSettingsRequest body, HttpServletRequest request) {
        return UpdateProtoMapper.toResponse(updates.saveSettings(body.getChecksEnabled(), body.getIncludePrereleases(),
            RequestUtil.requireSessionEmail(request)));
    }

    @PostMapping("/install")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public UpdateStatusResponse install(@RequestBody InstallUpdateRequest body, HttpServletRequest request) {
        return UpdateProtoMapper.toResponse(updates.install(body.getVersion(), body.getAcknowledgePluginUpdate(),
            RequestUtil.requireSessionEmail(request)));
    }
}

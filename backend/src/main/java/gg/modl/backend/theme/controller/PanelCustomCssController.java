package gg.modl.backend.theme.controller;

import gg.modl.backend.infrastructure.authorization.PanelAccessRule;
import gg.modl.backend.infrastructure.authorization.RequiresPanelPermission;
import gg.modl.backend.infrastructure.rest.RESTMappingV1;
import gg.modl.backend.infrastructure.rest.RequestUtil;
import gg.modl.backend.theme.service.CustomCssService;
import gg.modl.proto.modl.v1.PublicCustomCssEnvelope;
import gg.modl.proto.modl.v1.UpdatePublicCustomCssRequest;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(RESTMappingV1.PANEL_SETTINGS_PUBLIC_CSS)
@RequiresPanelPermission(rule = PanelAccessRule.SUPER_ADMIN)
@RequiredArgsConstructor
public class PanelCustomCssController {
    private final CustomCssService stylesheets;

    @GetMapping
    public PublicCustomCssEnvelope get() {
        return PublicThemeProtoMapper.toCssEnvelope(stylesheets.versioned());
    }

    @PutMapping
    public PublicCustomCssEnvelope update(@RequestBody UpdatePublicCustomCssRequest body, HttpServletRequest request) {
        return PublicThemeProtoMapper.toCssEnvelope(stylesheets.change(RequestUtil.getRequestServer(request),
            RequestUtil.requireSessionEmail(request), body.getExpectedVersion(), body.getCss()));
    }
}

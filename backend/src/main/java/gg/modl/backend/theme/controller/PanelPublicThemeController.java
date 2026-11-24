package gg.modl.backend.theme.controller;

import gg.modl.backend.infrastructure.authorization.RequiresPanelPermission;
import gg.modl.backend.infrastructure.rest.RESTMappingV1;
import gg.modl.backend.infrastructure.rest.RequestUtil;
import gg.modl.backend.role.service.PermissionService;
import gg.modl.backend.theme.service.PublicThemeService;
import gg.modl.backend.theme.service.ThemeBackgroundUploadService;
import gg.modl.proto.modl.v1.PublicThemeBackgroundResponse;
import gg.modl.proto.modl.v1.PublicThemeEnvelope;
import gg.modl.proto.modl.v1.UpdatePublicThemeRequest;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping(RESTMappingV1.PANEL_SETTINGS_PUBLIC_THEME)
@RequiresPanelPermission(view = PermissionService.ADMIN_SETTINGS_VIEW_CONTENT, modify = PermissionService.ADMIN_SETTINGS_MODIFY_CONTENT)
@RequiredArgsConstructor
public class PanelPublicThemeController {
    private final PublicThemeService themes;
    private final ThemeBackgroundUploadService backgrounds;

    @GetMapping
    public PublicThemeEnvelope get() {
        return PublicThemeProtoMapper.toEnvelope(themes.versioned());
    }

    @PutMapping
    public PublicThemeEnvelope update(@RequestBody UpdatePublicThemeRequest body, HttpServletRequest request) {
        return PublicThemeProtoMapper.toEnvelope(themes.change(RequestUtil.getRequestServer(request),
            RequestUtil.requireSessionEmail(request), body.getExpectedVersion(), PublicThemeProtoMapper.fromProto(body.getTheme())));
    }

    @PostMapping("/background")
    public PublicThemeBackgroundResponse uploadBackground(@RequestParam("image") MultipartFile file, HttpServletRequest request) {
        return PublicThemeBackgroundResponse.newBuilder()
            .setUrl(backgrounds.upload(RequestUtil.getRequestServer(request), file))
            .build();
    }
}

package gg.modl.backend.homepage.controller;

import gg.modl.backend.homepage.data.HomepageLayout;
import gg.modl.backend.homepage.service.HomepageCardService;
import gg.modl.backend.homepage.service.HomepageSettingsService;
import gg.modl.backend.infrastructure.authorization.RequiresPanelPermission;
import gg.modl.backend.infrastructure.rest.RESTMappingV1;
import gg.modl.backend.infrastructure.rest.RequestUtil;
import gg.modl.backend.role.service.PermissionService;
import gg.modl.backend.server.data.Server;
import gg.modl.backend.settings.service.VersionedSettings;
import gg.modl.proto.modl.v1.HomepageSettingsEnvelope;
import gg.modl.proto.modl.v1.UpdateHomepageSettingsRequest;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(RESTMappingV1.PANEL_SETTINGS_HOMEPAGE)
@RequiresPanelPermission(view = PermissionService.ADMIN_SETTINGS_VIEW_CONTENT, modify = PermissionService.ADMIN_SETTINGS_MODIFY_CONTENT)
@RequiredArgsConstructor
public class PanelHomepageSettingsController {
    private final HomepageSettingsService settings;
    private final HomepageCardService cards;

    @GetMapping
    public HomepageSettingsEnvelope get(HttpServletRequest request) {
        return envelope(RequestUtil.getRequestServer(request), settings.versioned());
    }

    @PutMapping
    public HomepageSettingsEnvelope update(@RequestBody UpdateHomepageSettingsRequest body, HttpServletRequest request) {
        Server server = RequestUtil.getRequestServer(request);
        VersionedSettings<HomepageLayout> saved = settings.change(server, RequestUtil.requireSessionEmail(request), body.getExpectedVersion(),
            HomepageSettingsProtoMapper.layoutOf(body.getSettings()), HomepageSettingsProtoMapper.cardsOf(body.getSettings()));
        return envelope(server, saved);
    }

    private HomepageSettingsEnvelope envelope(Server server, VersionedSettings<HomepageLayout> layout) {
        return HomepageSettingsProtoMapper.toEnvelope(layout, cards.getAllCardsEnriched(server));
    }
}

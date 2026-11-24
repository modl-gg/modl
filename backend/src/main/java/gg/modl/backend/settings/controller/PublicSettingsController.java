package gg.modl.backend.settings.controller;

import gg.modl.backend.auth.controller.SignInProtoMapper;
import gg.modl.backend.auth.settings.SignInSettingsService;
import gg.modl.backend.email.EmailCapability;
import gg.modl.backend.infrastructure.rest.RESTMappingV1;
import gg.modl.backend.infrastructure.rest.RequestUtil;
import gg.modl.backend.server.data.Server;
import gg.modl.backend.settings.data.GeneralSettings;
import gg.modl.backend.settings.data.TicketFormSettings;
import gg.modl.backend.settings.service.GeneralSettingsService;
import gg.modl.backend.settings.service.TicketFormSettingsService;
import gg.modl.backend.theme.controller.PublicThemeProtoMapper;
import gg.modl.backend.theme.service.CustomCssService;
import gg.modl.backend.theme.service.PublicThemeService;
import gg.modl.proto.modl.v1.PublicSettingsResponse;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(RESTMappingV1.PUBLIC_SETTINGS)
@RequiredArgsConstructor
public class PublicSettingsController {
    private final GeneralSettingsService generalSettingsService;
    private final TicketFormSettingsService ticketFormSettingsService;
    private final EmailCapability emailCapability;
    private final SignInSettingsService signInSettings;
    private final PublicThemeService themes;
    private final CustomCssService stylesheets;

    @GetMapping
    public PublicSettingsResponse getPublicSettings(HttpServletRequest request) {
        Server server = RequestUtil.getRequestServer(request);
        GeneralSettings generalSettings = generalSettingsService.getGeneralSettings(server);
        TicketFormSettings ticketForms = ticketFormSettingsService.getTicketFormSettings(server);
        return PanelSettingsProtoMapper.toPublicSettingsResponse(new gg.modl.backend.settings.dto.response.PublicSettingsResponse(
            true,
            generalSettings.getServerDisplayName(),
            generalSettings.getPanelIconUrl(),
            generalSettings.getHomepageIconUrl(),
            ticketFormSettingsService.buildTicketFormsResponse(ticketForms),
            generalSettings.getDefaultLanguage(),
            emailCapability.isDeliveryEnabled()
        )).toBuilder()
            .setSignInMethod(SignInProtoMapper.toProto(signInSettings.current().method()))
            .setTheme(PublicThemeProtoMapper.toProto(themes.current()))
            .setCustomCssRevision(stylesheets.published().revision())
            .build();
    }
}

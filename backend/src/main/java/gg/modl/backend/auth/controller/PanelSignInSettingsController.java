package gg.modl.backend.auth.controller;

import gg.modl.backend.auth.settings.SignInSettings;
import gg.modl.backend.auth.settings.SignInSettingsAdministration;
import gg.modl.backend.infrastructure.authorization.PanelAccessRule;
import gg.modl.backend.infrastructure.authorization.RequiresPanelPermission;
import gg.modl.backend.infrastructure.exception.ValidationException;
import gg.modl.backend.infrastructure.rest.RESTMappingV1;
import gg.modl.backend.infrastructure.rest.RequestUtil;
import gg.modl.proto.modl.v1.SignInSettingsResponse;
import gg.modl.proto.modl.v1.UpdateSignInSettingsRequest;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(RESTMappingV1.PANEL_SETTINGS_SIGN_IN)
@RequiresPanelPermission(rule = PanelAccessRule.SUPER_ADMIN)
@RequiredArgsConstructor
public class PanelSignInSettingsController {
    private final SignInSettingsAdministration administration;

    @GetMapping
    public SignInSettingsResponse get(HttpServletRequest request) {
        return SignInProtoMapper.toProto(administration.overview(RequestUtil.getRequestServer(request)));
    }

    @PutMapping
    public SignInSettingsResponse update(@RequestBody UpdateSignInSettingsRequest body, HttpServletRequest request) {
        SignInSettings requested = new SignInSettings(
            SignInProtoMapper.fromProto(body.getMethod()).orElseThrow(() -> new ValidationException("A sign-in method is required")),
            body.getSecondFactorRequired());
        return SignInProtoMapper.toProto(administration.update(RequestUtil.getRequestServer(request), RequestUtil.requireSession(request),
            body.getExpectedVersion(), requested));
    }
}

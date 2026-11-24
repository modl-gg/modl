package gg.modl.backend.settings.controller;

import gg.modl.backend.email.MailSettingsService;
import gg.modl.backend.infrastructure.authorization.PanelAccessRule;
import gg.modl.backend.infrastructure.authorization.RequiresPanelPermission;
import gg.modl.backend.infrastructure.rest.RESTMappingV1;
import gg.modl.backend.infrastructure.rest.RequestUtil;
import gg.modl.proto.modl.v1.MailSettingsResponse;
import gg.modl.proto.modl.v1.TestMailRequest;
import gg.modl.proto.modl.v1.TestMailResponse;
import gg.modl.proto.modl.v1.UpdateMailSettingsRequest;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(RESTMappingV1.PANEL_SETTINGS + "/mail")
@RequiresPanelPermission(rule = PanelAccessRule.SUPER_ADMIN)
@RequiredArgsConstructor
public class PanelMailSettingsController {
    private final MailSettingsService mailSettingsService;

    @GetMapping
    public MailSettingsResponse getSettings() {
        return MailSettingsProtoMapper.toResponse(mailSettingsService.view());
    }

    @PutMapping
    public MailSettingsResponse updateSettings(@RequestBody UpdateMailSettingsRequest body) {
        return MailSettingsProtoMapper.toResponse(mailSettingsService.save(
            MailSettingsProtoMapper.toInput(body.getSettings()), body.hasPassword() ? Optional.of(body.getPassword()) : Optional.empty()));
    }

    @DeleteMapping
    public MailSettingsResponse clearSettings() {
        return MailSettingsProtoMapper.toResponse(mailSettingsService.delete());
    }

    @PostMapping("/test")
    @ChangesNoSettings
    public TestMailResponse sendTest(@RequestBody TestMailRequest body, HttpServletRequest request) {
        return MailSettingsProtoMapper.toResponse(mailSettingsService.sendTest(
            body.hasSettings() ? Optional.of(MailSettingsProtoMapper.toInput(body.getSettings())) : Optional.empty(),
            body.hasPassword() ? Optional.of(body.getPassword()) : Optional.empty(),
            RequestUtil.requireSessionEmail(request)));
    }
}

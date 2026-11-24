package gg.modl.backend.settings.controller;

import gg.modl.backend.infrastructure.authorization.PanelAccessRule;
import gg.modl.backend.infrastructure.authorization.RequiresPanelPermission;
import gg.modl.backend.infrastructure.rest.RESTMappingV1;
import gg.modl.backend.infrastructure.rest.RequestUtil;
import gg.modl.backend.iplookup.model.IpLookupAccess;
import gg.modl.backend.iplookup.probe.IpLookupProbeService;
import gg.modl.backend.iplookup.settings.PlayerAddressSettingsService;
import gg.modl.backend.iplookup.settings.PlayerAddressSettingsView;
import gg.modl.backend.server.data.Server;
import gg.modl.proto.modl.v1.PlayerAddressLookupTestResponse;
import gg.modl.proto.modl.v1.PlayerAddressSettingsResponse;
import gg.modl.proto.modl.v1.SavePlayerAddressKeyRequest;
import gg.modl.proto.modl.v1.TestPlayerAddressLookupRequest;
import gg.modl.proto.modl.v1.UpdatePlayerAddressSettingsRequest;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
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
@RequestMapping(RESTMappingV1.PANEL_SETTINGS + "/player-addresses")
@RequiresPanelPermission(rule = PanelAccessRule.SUPER_ADMIN)
@RequiredArgsConstructor
public class PanelPlayerAddressSettingsController {
    private final PlayerAddressSettingsService settingsService;
    private final IpLookupProbeService probeService;

    @GetMapping
    public PlayerAddressSettingsResponse getSettings() {
        return toResponse(settingsService.view());
    }

    @PutMapping
    public PlayerAddressSettingsResponse updateSettings(@RequestBody @Valid UpdatePlayerAddressSettingsRequest body,
                                                        HttpServletRequest request) {
        Server server = RequestUtil.getRequestServer(request);
        String actor = RequestUtil.requireSessionEmail(request);
        PlayerAddressSettingsView view = settingsService.view();
        if (body.hasProvider()) {
            view = settingsService.selectProvider(server, actor, body.getProvider());
        }
        if (body.hasSharedAccountLimit()) {
            view = settingsService.setSharedAccountLimit(server, actor, body.getSharedAccountLimit());
        }
        return toResponse(view);
    }

    @PutMapping("/keys/{provider}")
    public PlayerAddressSettingsResponse saveKey(@PathVariable String provider,
                                                 @RequestBody @Valid SavePlayerAddressKeyRequest body,
                                                 HttpServletRequest request) {
        return toResponse(settingsService.saveKey(RequestUtil.getRequestServer(request),
            RequestUtil.requireSessionEmail(request), provider, body.getApiKey()));
    }

    @DeleteMapping("/keys/{provider}")
    public PlayerAddressSettingsResponse deleteKey(@PathVariable String provider, HttpServletRequest request) {
        return toResponse(settingsService.deleteKey(RequestUtil.getRequestServer(request),
            RequestUtil.requireSessionEmail(request), provider));
    }

    @PostMapping("/notice/dismiss")
    public PlayerAddressSettingsResponse dismissNotice() {
        return toResponse(settingsService.dismissNotice());
    }

    @PostMapping("/choice/dismiss")
    public PlayerAddressSettingsResponse dismissChoicePrompt() {
        return toResponse(settingsService.dismissChoicePrompt());
    }

    @PostMapping("/test")
    @ChangesNoSettings
    public PlayerAddressLookupTestResponse testLookup(@RequestBody @Valid TestPlayerAddressLookupRequest body) {
        IpLookupAccess access = settingsService.testAccess(body.getProvider(), body.hasApiKey() ? body.getApiKey() : null);
        return PlayerAddressSettingsProtoMapper.toResponse(probeService.test(access));
    }

    private static PlayerAddressSettingsResponse toResponse(PlayerAddressSettingsView view) {
        return PlayerAddressSettingsProtoMapper.toResponse(view);
    }
}

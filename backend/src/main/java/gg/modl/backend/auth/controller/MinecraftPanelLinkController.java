package gg.modl.backend.auth.controller;

import gg.modl.backend.auth.link.AccountLinkResult;
import gg.modl.backend.auth.link.LoginLinkIssue;
import gg.modl.backend.auth.link.MinecraftAccountLinkService;
import gg.modl.backend.auth.link.PanelLoginLinkService;
import gg.modl.backend.auth.link.PlayerLinkThrottle;
import gg.modl.backend.infrastructure.rest.RESTMappingV1;
import gg.modl.backend.infrastructure.rest.RequestUtil;
import gg.modl.backend.infrastructure.validation.RegExpConstants;
import gg.modl.backend.infrastructure.validation.RequestValidationLimits;
import gg.modl.backend.server.data.Server;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(RESTMappingV1.MINECRAFT_PANEL)
@RequiredArgsConstructor
public class MinecraftPanelLinkController {
    private static final int MAX_LINK_CODE_LENGTH = 32;

    private final MinecraftAccountLinkService minecraftAccountLinkService;
    private final PanelLoginLinkService panelLoginLinkService;
    private final PlayerLinkThrottle playerLinkThrottle;

    @PostMapping("/link-account")
    public ResponseEntity<Map<String, Object>> linkAccount(
        @RequestBody @Valid LinkAccountRequest request,
        HttpServletRequest httpRequest
    ) {
        playerLinkThrottle.requireAdmission(request.minecraftUuid());
        Server server = RequestUtil.getRequestServer(httpRequest);
        AccountLinkResult result = minecraftAccountLinkService.redeem(
            server, request.code(), request.minecraftUuid(), request.minecraftUsername());
        return switch (result) {
            case LINKED -> ResponseEntity.ok(Map.of("linked", true));
            case INVALID_CODE -> ResponseEntity.badRequest().body(Map.of("error", "invalid_code"));
            case ALREADY_LINKED -> ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", "already_linked"));
        };
    }

    @PostMapping("/login-link")
    public ResponseEntity<Map<String, Object>> loginLink(
        @RequestBody @Valid LoginLinkRequest request,
        HttpServletRequest httpRequest
    ) {
        playerLinkThrottle.requireAdmission(request.minecraftUuid());
        Server server = RequestUtil.getRequestServer(httpRequest);
        return switch (panelLoginLinkService.issue(server, request.minecraftUuid())) {
            case LoginLinkIssue.Issued issued -> ResponseEntity.ok(Map.of(
                "url", issued.url(),
                "expiresInSeconds", issued.expiresInSeconds()
            ));
            case LoginLinkIssue.NotLinked ignored -> ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "not_linked"));
            case LoginLinkIssue.NotOnline ignored -> ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", "not_online"));
        };
    }

    public record LinkAccountRequest(
        @NotBlank @Pattern(regexp = RegExpConstants.UUID) String minecraftUuid,
        @NotBlank @Pattern(regexp = RegExpConstants.MINECRAFT_USERNAME)
        @Size(max = RequestValidationLimits.LOG_USERNAME_MAX_LENGTH) String minecraftUsername,
        @NotBlank @Size(max = MAX_LINK_CODE_LENGTH) String code
    ) {
    }

    public record LoginLinkRequest(
        @NotBlank @Pattern(regexp = RegExpConstants.UUID) String minecraftUuid
    ) {
    }
}

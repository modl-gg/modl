package gg.modl.backend.player.controller;

import gg.modl.backend.infrastructure.rest.RESTMappingV1;
import gg.modl.backend.infrastructure.rest.RequestUtil;
import gg.modl.backend.infrastructure.validation.RegExpConstants;
import gg.modl.backend.player.dto.response.PlayerStandingView;
import gg.modl.backend.player.service.PlayerStandingService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(RESTMappingV1.MINECRAFT_PLAYERS)
@RequiredArgsConstructor
@Validated
public class MinecraftPlayerStandingController {
    private final PlayerStandingService playerStandingService;

    @GetMapping("/{uuid}/standing")
    public ResponseEntity<PlayerStandingView> getStanding(
        @PathVariable @Pattern(regexp = RegExpConstants.UUID) String uuid,
        HttpServletRequest request
    ) {
        return ResponseEntity.ok(playerStandingService.standingOf(RequestUtil.getRequestServer(request), uuid));
    }
}

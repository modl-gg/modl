package gg.modl.backend.replay.controller;

import gg.modl.backend.infrastructure.authorization.PanelAccessRule;
import gg.modl.backend.infrastructure.authorization.RequiresPanelPermission;
import gg.modl.backend.infrastructure.rest.RESTMappingV1;
import gg.modl.backend.infrastructure.rest.RequestUtil;
import gg.modl.backend.replay.share.ReplayShareService;
import gg.modl.proto.modl.v1.CreateReplayShareRequest;
import gg.modl.proto.modl.v1.CreateReplayShareResponse;
import gg.modl.proto.modl.v1.ReplaySharesResponse;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(RESTMappingV1.PANEL_REPLAYS + "/{replayId}/shares")
@RequiresPanelPermission(rule = PanelAccessRule.REPLAY_SHARING)
@RequiredArgsConstructor
public class PanelReplayShareController {
    private final ReplayShareService shareService;

    @PostMapping
    public CreateReplayShareResponse create(@PathVariable String replayId, @RequestBody CreateReplayShareRequest body,
                                            HttpServletRequest request) {
        return ReplayShareProtoMapper.toCreated(shareService.create(RequestUtil.getRequestServer(request),
            RequestUtil.requireSessionEmail(request), replayId, ReplayShareProtoMapper.expiryOf(body.getExpiry())));
    }

    @GetMapping
    public ReplaySharesResponse list(@PathVariable String replayId) {
        return ReplayShareProtoMapper.toList(shareService.list(replayId));
    }

    @DeleteMapping("/{shareId}")
    public ReplaySharesResponse revoke(@PathVariable String replayId, @PathVariable String shareId, HttpServletRequest request) {
        return ReplayShareProtoMapper.toList(shareService.revoke(RequestUtil.getRequestServer(request),
            RequestUtil.requireSessionEmail(request), replayId, shareId));
    }
}

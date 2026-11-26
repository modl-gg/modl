package gg.modl.backend.replay.controller;

import gg.modl.backend.infrastructure.authorization.PanelAccessRule;
import gg.modl.backend.infrastructure.authorization.RequiresPanelPermission;
import gg.modl.backend.infrastructure.exception.ResourceNotFoundException;
import gg.modl.backend.infrastructure.rest.RESTMappingV1;
import gg.modl.backend.infrastructure.rest.RequestUtil;
import gg.modl.backend.replay.service.ReplayService;
import gg.modl.backend.role.service.PermissionService;
import gg.modl.backend.server.data.Server;
import gg.modl.proto.modl.v1.PanelReplayResponse;
import gg.modl.proto.modl.v1.ReplayLabelResponse;
import gg.modl.proto.modl.v1.SubmitReplayLabelsRequest;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.Resource;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(RESTMappingV1.PANEL_REPLAYS)
@RequiredArgsConstructor
public class PanelReplayController {
    static final String NOT_FOUND_CODE = "REPLAY_NOT_FOUND";

    private final ReplayService replayService;
    private final ReplayProtoMapper mapper;

    @GetMapping("/{replayId}")
    @RequiresPanelPermission(rule = PanelAccessRule.PLAYER_ACCESS)
    public PanelReplayResponse getReplay(@PathVariable String replayId) {
        return replayService.getPanelReplay(replayId)
            .map(mapper::toPanelReplay)
            .orElseThrow(() -> new ResourceNotFoundException("Replay not found", NOT_FOUND_CODE));
    }

    @GetMapping("/{replayId}/file")
    @RequiresPanelPermission(rule = PanelAccessRule.PLAYER_ACCESS)
    public ResponseEntity<Resource> getReplayFile(@PathVariable String replayId) {
        return replayService.completedFile(replayId)
            .map(ReplayFileResponses::serve)
            .orElseThrow(() -> new ResourceNotFoundException("Replay not found", NOT_FOUND_CODE));
    }

    @PostMapping("/{replayId}/label")
    @RequiresPanelPermission(PermissionService.PUNISHMENT_MODIFY)
    public ReplayLabelResponse submitLabels(
        @PathVariable String replayId,
        @RequestBody SubmitReplayLabelsRequest request,
        HttpServletRequest httpRequest
    ) {
        Server server = RequestUtil.getRequestServer(httpRequest);
        ReplayService.SubmitLabelsResult result =
            replayService.submitLabels(server, replayId, mapper.toReplayLabels(request));
        if (result == ReplayService.SubmitLabelsResult.NOT_FOUND) {
            throw new ResourceNotFoundException("Replay not found", NOT_FOUND_CODE);
        }
        return mapper.labelResponse("ok");
    }
}

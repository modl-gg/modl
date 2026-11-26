package gg.modl.backend.replay.controller;

import gg.modl.backend.infrastructure.exception.ResourceNotFoundException;
import gg.modl.backend.infrastructure.rest.RESTMappingV1;
import gg.modl.backend.infrastructure.rest.RequestUtil;
import gg.modl.backend.infrastructure.web.ClientIpResolver;
import gg.modl.backend.replay.share.ReplayShareService;
import gg.modl.proto.modl.v1.SharedReplayResponse;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.Resource;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(RESTMappingV1.PUBLIC_SHARED_REPLAYS + "/{token}")
@RequiredArgsConstructor
public class PublicSharedReplayController {
    private final ReplayShareService shareService;
    private final ClientIpResolver clientIpResolver;

    @GetMapping
    public SharedReplayResponse sharedReplay(@PathVariable String token, HttpServletRequest request) {
        return shareService.open(RequestUtil.getRequestServer(request), token, clientIpResolver.resolveNetwork(request))
            .map(ReplayShareProtoMapper::toShared)
            .orElseThrow(PublicSharedReplayController::notFound);
    }

    @GetMapping("/file")
    public ResponseEntity<Resource> sharedReplayFile(@PathVariable String token) {
        return shareService.openFile(token)
            .map(ReplayFileResponses::serve)
            .orElseThrow(PublicSharedReplayController::notFound);
    }

    private static ResourceNotFoundException notFound() {
        return new ResourceNotFoundException("Shared replay not found", ReplayShareService.SHARE_NOT_FOUND);
    }
}

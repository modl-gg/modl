package gg.modl.backend.replay.service;

import gg.modl.backend.replay.repository.ReplayShareRepository;
import gg.modl.backend.server.data.Server;
import java.time.Clock;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
class OpenShareReplayHold implements ReplayHold {
    private final ReplayShareRepository shareRepository;
    private final Clock clock;

    @Override
    public Set<String> heldReplayIds(Server server, List<String> replayIds) {
        return shareRepository.findReplayIdsWithOpenShare(replayIds, clock.instant());
    }
}

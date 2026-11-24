package gg.modl.backend.replay.service;

import gg.modl.backend.player.repository.PunishmentRepository;
import gg.modl.backend.server.data.Server;
import gg.modl.backend.ticket.repository.TicketRepository;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
class ActivePunishmentReplayHold implements ReplayHold {
    private final TicketRepository ticketRepository;
    private final PunishmentRepository punishmentRepository;
    private final Clock clock;

    @Override
    public Set<String> heldReplayIds(Server server, List<String> replayIds) {
        Map<String, String> replayIdByTicketId = ticketRepository.findReplayIdsByTicketId(replayIds);
        if (replayIdByTicketId.isEmpty()) {
            return Set.of();
        }
        return punishmentRepository.findTicketIdsHeldByActiveOrUnstarted(replayIdByTicketId.keySet(), clock.instant()).stream()
            .map(replayIdByTicketId::get)
            .filter(Objects::nonNull)
            .collect(Collectors.toSet());
    }
}

package gg.modl.backend.replay.service;

import gg.modl.backend.server.data.Server;
import gg.modl.backend.ticket.repository.TicketRepository;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
class OpenTicketReplayHold implements ReplayHold {
    private final TicketRepository ticketRepository;

    @Override
    public Set<String> heldReplayIds(Server server, List<String> replayIds) {
        return ticketRepository.findReplayIdsReferencedByUnresolvedTicket(replayIds);
    }
}

package gg.modl.backend.ticket.service;

import gg.modl.backend.instance.PublicUrls;
import gg.modl.backend.ticket.data.Ticket;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class TicketPublicLinks {
    private final PublicUrls publicUrls;

    @Nullable
    public String of(Ticket ticket) {
        String accessKey = ticket.getPublicAccessKey();
        if (ticket.getId() == null || accessKey == null || accessKey.isEmpty()) {
            return null;
        }
        return publicUrls.ticket(ticket.getId(), accessKey);
    }

    @Nullable
    public String replay(Ticket ticket) {
        String replayId = ticket.getReplayId();
        return replayId == null || replayId.isBlank() ? ticket.getReplayUrl() : publicUrls.replay(replayId);
    }
}

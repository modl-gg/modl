package gg.modl.backend.replay.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import gg.modl.backend.player.repository.PunishmentRepository;
import gg.modl.backend.server.data.Server;
import gg.modl.backend.support.MutableClock;
import gg.modl.backend.support.TestServers;
import gg.modl.backend.ticket.repository.TicketRepository;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ActivePunishmentReplayHoldTest {
    private static final Instant NOW = Instant.parse("2026-11-21T12:00:00Z");

    private final Server server = TestServers.server("server", "admin@example.com");
    private TicketRepository ticketRepository;
    private PunishmentRepository punishmentRepository;
    private ActivePunishmentReplayHold hold;

    @BeforeEach
    void setUp() {
        ticketRepository = mock(TicketRepository.class);
        punishmentRepository = mock(PunishmentRepository.class);
        hold = new ActivePunishmentReplayHold(ticketRepository, punishmentRepository, new MutableClock(NOW));
    }

    @Test
    void replaysBehindAPunishmentThatIsInForceOrNotYetStartedAreKept() {
        List<String> replayIds = List.of("r-active", "r-expired", "r-unpunished");
        when(ticketRepository.findReplayIdsByTicketId(replayIds))
            .thenReturn(Map.of("T-1", "r-active", "T-2", "r-expired", "T-3", "r-unpunished"));
        when(punishmentRepository.findTicketIdsHeldByActiveOrUnstarted(Set.of("T-1", "T-2", "T-3"), NOW)).thenReturn(Set.of("T-1"));

        assertThat(hold.heldReplayIds(server, replayIds)).containsExactly("r-active");
    }

    @Test
    void oneActivePunishmentIsEnoughWhenSeveralTicketsShareAReplay() {
        when(ticketRepository.findReplayIdsByTicketId(List.of("r"))).thenReturn(Map.of("T-1", "r", "T-2", "r"));
        when(punishmentRepository.findTicketIdsHeldByActiveOrUnstarted(Set.of("T-1", "T-2"), NOW)).thenReturn(Set.of("T-2"));

        assertThat(hold.heldReplayIds(server, List.of("r"))).containsExactly("r");
    }

    @Test
    void replaysWithoutTicketsNeedNoPunishmentLookup() {
        when(ticketRepository.findReplayIdsByTicketId(List.of("r1"))).thenReturn(Map.of());

        assertThat(hold.heldReplayIds(server, List.of("r1"))).isEmpty();
        verifyNoInteractions(punishmentRepository);
    }
}

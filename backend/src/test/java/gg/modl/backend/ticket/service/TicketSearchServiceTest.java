package gg.modl.backend.ticket.service;

import gg.modl.backend.support.TestServers;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import gg.modl.backend.ticket.repository.TicketRepository;
import gg.modl.backend.server.data.Server;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class TicketSearchServiceTest {

    @Mock
    private TicketRepository ticketRepository;

    private TicketSearchService ticketSearchService;

    @BeforeEach
    void setUp() {
        ticketSearchService = new TicketSearchService(ticketRepository);
    }

    @Test
    void getTicketsByPlayerLowercasesUuidBeforeQueryingRepository() {
        Server server = TestServers.server("server", "admin@example.com");
        when(ticketRepository.findByPlayer(any())).thenReturn(List.of());

        ticketSearchService.getTicketsByPlayer(server, "AAAAAAAA-BBBB-CCCC-DDDD-EEEEEEEEEEEE");

        verify(ticketRepository).findByPlayer("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
    }
}

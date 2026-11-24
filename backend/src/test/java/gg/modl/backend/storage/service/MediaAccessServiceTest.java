package gg.modl.backend.storage.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import gg.modl.backend.infrastructure.util.IdGenerator;
import gg.modl.backend.server.data.Server;
import gg.modl.backend.support.TestServers;
import gg.modl.backend.ticket.data.Ticket;
import gg.modl.backend.ticket.data.TicketCategory;
import gg.modl.backend.ticket.service.PublicRecordAccessService;
import gg.modl.backend.ticket.service.TicketAccessKeys;
import gg.modl.backend.ticket.service.TicketEmailVerificationService;
import gg.modl.backend.ticket.service.TicketService;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class MediaAccessServiceTest {
    private static final String ACCESS_KEY = "AbCdEfGhIjKlMnOpQrStUvWxYz0123456789-_abcde";

    private final Server server = TestServers.server("Demo", "admin@example.com");
    private final TicketService ticketService = mock(TicketService.class);
    private MediaAccessService service;

    @BeforeEach
    void setUp() {
        PublicRecordAccessService accessService = new PublicRecordAccessService(
            new TicketAccessKeys(new IdGenerator()), mock(TicketEmailVerificationService.class), () -> false);
        service = new MediaAccessService(ticketService, accessService);
        Ticket appeal = Ticket.builder().id("APPEAL-100001").type(TicketCategory.APPEAL).publicAccessKey(ACCESS_KEY).build();
        when(ticketService.getTicketRaw(server, "APPEAL-100001")).thenReturn(Optional.of(appeal));
    }

    @Test
    void uploadsWithoutTheKeyAreDeniedBeforeTheRecordTypeIsRevealed() {
        MediaAccessService.AccessResult asTicket = service.validatePublicUploadAccess(server, "ticket", "APPEAL-100001", null);
        MediaAccessService.AccessResult asAppeal = service.validatePublicUploadAccess(server, "appeal", "APPEAL-100001", null);

        assertThat(asTicket.status()).isEqualTo(MediaAccessService.AccessStatus.DENIED);
        assertThat(asTicket.error()).isEqualTo(asAppeal.error());
        assertThat(asTicket.error()).doesNotContain("appeal");
    }

    @Test
    void keyHoldersGetTheTypeCheck() {
        assertThat(service.validatePublicUploadAccess(server, "appeal", "APPEAL-100001", ACCESS_KEY).isAllowed()).isTrue();
        assertThat(service.validatePublicUploadAccess(server, "ticket", "APPEAL-100001", ACCESS_KEY).error())
            .isEqualTo("Appeal uploads must use uploadType=appeal");
    }
}

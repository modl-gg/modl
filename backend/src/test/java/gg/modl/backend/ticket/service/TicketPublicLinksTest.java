package gg.modl.backend.ticket.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import gg.modl.backend.instance.InstanceContext;
import gg.modl.backend.instance.InstanceProperties;
import gg.modl.backend.instance.PublicUrls;
import gg.modl.backend.server.data.Server;
import gg.modl.backend.support.TestServers;
import gg.modl.backend.ticket.data.Ticket;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TicketPublicLinksTest {
    private Server server;
    private PublicUrls publicUrls;
    private TicketPublicLinks links;

    @BeforeEach
    void setUp() {
        InstanceContext instanceContext = mock(InstanceContext.class);
        server = TestServers.server("Server", "owner@example.com");
        when(instanceContext.instance()).thenReturn(server);
        publicUrls = new PublicUrls(new InstanceProperties(null, null, null, null, null, null), instanceContext, 8080);
        links = new TicketPublicLinks(publicUrls);
    }

    @Test
    void aReplayLinkStoredDuringSetupFollowsTheAddressSetAfterwards() {
        server.setPublicUrl("http://192.168.1.20:8080");
        Ticket imported = Ticket.builder().id("T-1").replayId("r-1").replayUrl(publicUrls.replayPath("r-1")).build();

        server.setPublicUrl("https://panel.example.com");

        assertThat(imported.getReplayUrl()).doesNotContain("192.168.1.20");
        assertThat(links.replay(imported)).isEqualTo("https://panel.example.com/replay?id=r-1");
    }

    @Test
    void aLiveTicketWithAnAbsoluteReplayLinkIsShownWithTheCurrentAddress() {
        server.setPublicUrl("https://panel.example.com");
        Ticket live = Ticket.builder().id("T-2").replayId("r-2").replayUrl("http://localhost:8080/replay?id=r-2").build();

        assertThat(links.replay(live)).isEqualTo("https://panel.example.com/replay?id=r-2");
    }

    @Test
    void aLinkThatIsNotAModlReplayIsKeptAsStored() {
        Ticket external = Ticket.builder().id("T-3").replayUrl("https://cdn.example/replay.modlreplay").build();

        assertThat(links.replay(external)).isEqualTo("https://cdn.example/replay.modlreplay");
    }

    @Test
    void aTicketWithoutAReplayHasNoReplayLink() {
        assertThat(links.replay(Ticket.builder().id("T-4").build())).isNull();
    }
}

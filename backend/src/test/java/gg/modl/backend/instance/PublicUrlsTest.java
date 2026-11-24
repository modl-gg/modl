package gg.modl.backend.instance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import gg.modl.backend.infrastructure.web.HttpOrigin;
import gg.modl.backend.server.data.Server;
import gg.modl.backend.support.TestServers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PublicUrlsTest {
    private InstanceContext instanceContext;
    private Server server;

    @BeforeEach
    void setUp() {
        instanceContext = mock(InstanceContext.class);
        server = TestServers.server("Server", "owner@example.com");
        when(instanceContext.instance()).thenReturn(server);
    }

    @Test
    void configuredUrlWinsOverTheStoredUrlAndIsManagedByConfig() {
        server.setPublicUrl("https://stored.example.com");
        PublicUrls urls = urls("https://configured.example.com");

        assertThat(urls.knownOrigin()).contains(new HttpOrigin("https", "configured.example.com", 443));
        assertThat(urls.isManagedByConfig()).isTrue();
        assertThat(urls.panel()).isEqualTo("https://configured.example.com");
    }

    @Test
    void storedUrlIsUsedWhenNothingIsConfigured() {
        server.setPublicUrl("https://stored.example.com:8443");
        PublicUrls urls = urls(null);

        assertThat(urls.knownOrigin()).contains(new HttpOrigin("https", "stored.example.com", 8443));
        assertThat(urls.isManagedByConfig()).isFalse();
    }

    @Test
    void unknownUrlFallsBackToLocalhostOnTheServerPort() {
        PublicUrls urls = urls(null);

        assertThat(urls.knownOrigin()).isEmpty();
        assertThat(urls.origin()).isEqualTo("http://localhost:18190");
    }

    @Test
    void ticketLinksCarryTheEncodedAccessKey() {
        server.setPublicUrl("https://panel.example.com");

        assertThat(urls(null).ticket("ABC-123", "k+y/=x"))
            .isEqualTo("https://panel.example.com/ticket/ABC-123?key=k%2By%2F%3Dx");
    }

    @Test
    void playerProfileLinksPointAtThePanelPlayerPage() {
        server.setPublicUrl("https://panel.example.com");

        assertThat(urls(null).playerProfile("0f8fad5b-d9cb-469f-a165-70867728950e"))
            .isEqualTo("https://panel.example.com/panel/player/0f8fad5b-d9cb-469f-a165-70867728950e");
    }

    @Test
    void replayLinksCarryTheEncodedReplayId() {
        server.setPublicUrl("https://panel.example.com");

        assertThat(urls(null).replay("r 1&x")).isEqualTo("https://panel.example.com/replay?id=r+1%26x");
    }

    @Test
    void theReplayPathCarriesNoOriginSoAStoredLinkFollowsTheCurrentAddress() {
        server.setPublicUrl("http://192.168.1.20:8080");

        assertThat(urls(null).replayPath("r 1&x")).isEqualTo("/replay?id=r+1%26x");
        assertThat(urls(null).replay("r 1&x")).isEqualTo(urls(null).origin() + urls(null).replayPath("r 1&x"));
    }

    @Test
    void hostNeutralSetupPageNamesTheServerPortWithoutGuessingAHost() {
        assertThat(urls(null).hostNeutralSetupPage()).isEqualTo("http://<this server's address>:18190/setup");
    }

    @Test
    void theSetupPageCarriesNoSecret() {
        server.setPublicUrl("https://panel.example.com");

        assertThat(urls(null).setupPage()).isEqualTo("https://panel.example.com/setup");
    }

    private PublicUrls urls(String configuredPublicUrl) {
        InstanceProperties properties = new InstanceProperties(configuredPublicUrl, null, null, null, null, null);
        return new PublicUrls(properties, instanceContext, 18190);
    }
}

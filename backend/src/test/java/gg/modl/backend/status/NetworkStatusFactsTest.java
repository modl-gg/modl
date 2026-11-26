package gg.modl.backend.status;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import gg.modl.backend.infrastructure.web.HttpOrigin;
import gg.modl.backend.instance.InstanceProperties;
import gg.modl.backend.instance.PublicUrls;
import gg.modl.backend.instance.reachability.PanelReachability;
import gg.modl.backend.instance.reachability.PanelReachabilityCheck;
import gg.modl.backend.status.facts.NetworkStatusFacts;
import gg.modl.backend.tunnel.TunnelStatus;
import gg.modl.backend.tunnel.TunnelPhase;
import gg.modl.backend.tunnel.TunnelState;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class NetworkStatusFactsTest {
    private static final String API_KEY = "instance-api-key-that-must-never-be-reported-0123456789";
    private static final String TOKEN = "eyJhIjoiMDEyMzQ1Njc4OWFiY2RlZiIsInQiOiJ0dW5uZWwiLCJzIjoic2VjcmV0In0=";

    @Test
    void theReportCarriesTheAddressButNeverTheConfiguredSecrets() {
        PublicUrls urls = mock(PublicUrls.class);
        when(urls.knownOrigin()).thenReturn(HttpOrigin.parseExact("https://panel.example.com"));
        PanelReachabilityCheck reachability = mock(PanelReachabilityCheck.class);
        when(reachability.latest()).thenReturn(PanelReachability.NOT_CHECKED);
        InstanceProperties properties = new InstanceProperties("https://panel.example.com", "owner@example.com", null, API_KEY,
            "internal-key-0123456789-0123456789", true);
        NetworkStatusFacts facts = new NetworkStatusFacts(urls, properties, reachability, tunnel(TunnelState.OFF), "0.0.0.0", 8080);

        String report = new SupportReportService(List.of(facts), Clock.systemUTC()).report().render();

        assertThat(report).contains("Panel URL: https://panel.example.com", "Listening on: 0.0.0.0:8080", "Cloudflare Tunnel: off")
            .doesNotContain(API_KEY).doesNotContain("internal-key").doesNotContain("owner@example.com");
    }

    @Test
    void aMissingPanelUrlIsAWarning() {
        PublicUrls urls = mock(PublicUrls.class);
        when(urls.knownOrigin()).thenReturn(Optional.empty());
        PanelReachabilityCheck reachability = mock(PanelReachabilityCheck.class);
        when(reachability.latest()).thenReturn(PanelReachability.NOT_CHECKED);
        NetworkStatusFacts facts = new NetworkStatusFacts(urls, new InstanceProperties(null, null, null, null, null, false),
            reachability, tunnel(TunnelState.OFF), "0.0.0.0", 8080);

        assertThat(facts.facts()).anyMatch(fact -> fact.item() == StatusItem.PANEL_URL && fact.level() == StatusLevel.WARNING);
    }

    @Test
    void theTunnelReportsItsConnectionAndAddressButNeverItsToken() {
        PublicUrls urls = mock(PublicUrls.class);
        when(urls.knownOrigin()).thenReturn(HttpOrigin.parseExact("https://panel.example.com"));
        PanelReachabilityCheck reachability = mock(PanelReachabilityCheck.class);
        when(reachability.latest()).thenReturn(PanelReachability.NOT_CHECKED);
        TunnelStatus tunnel = tunnel(new TunnelState(TunnelPhase.CONNECTED, null));
        NetworkStatusFacts facts = new NetworkStatusFacts(urls, new InstanceProperties(null, null, null, null, null, true),
            reachability, tunnel, "127.0.0.1", 8080);

        String report = new SupportReportService(List.of(facts), Clock.systemUTC()).report().render();

        assertThat(report).contains("Cloudflare Tunnel: connected, https://panel.example.com").doesNotContain(TOKEN);
    }

    @Test
    void aTunnelThatCannotConnectIsAWarningAndOneThatCannotStartIsAProblem() {
        assertThat(tunnelFact(new TunnelState(TunnelPhase.NOT_CONNECTED, null)))
            .satisfies(fact -> assertThat(fact.level()).isEqualTo(StatusLevel.WARNING))
            .satisfies(fact -> assertThat(fact.value()).isEqualTo("not connected, https://panel.example.com"));
        assertThat(tunnelFact(TunnelState.unavailable("no cloudflared build for linux riscv64")))
            .satisfies(fact -> assertThat(fact.level()).isEqualTo(StatusLevel.PROBLEM))
            .satisfies(fact -> assertThat(fact.value()).isEqualTo("cannot start: no cloudflared build for linux riscv64"));
    }

    private static StatusFact tunnelFact(TunnelState state) {
        PublicUrls urls = mock(PublicUrls.class);
        when(urls.knownOrigin()).thenReturn(HttpOrigin.parseExact("https://panel.example.com"));
        PanelReachabilityCheck reachability = mock(PanelReachabilityCheck.class);
        when(reachability.latest()).thenReturn(PanelReachability.NOT_CHECKED);
        NetworkStatusFacts facts = new NetworkStatusFacts(urls, new InstanceProperties(null, null, null, null, null, true),
            reachability, tunnel(state), "127.0.0.1", 8080);
        return facts.facts().stream().filter(fact -> fact.item() == StatusItem.TUNNEL).findFirst().orElseThrow();
    }

    private static TunnelStatus tunnel(TunnelState state) {
        TunnelStatus tunnel = mock(TunnelStatus.class);
        when(tunnel.state()).thenReturn(state);
        when(tunnel.enabled()).thenReturn(state.phase() != TunnelPhase.OFF);
        when(tunnel.address()).thenReturn("https://panel.example.com");
        return tunnel;
    }
}

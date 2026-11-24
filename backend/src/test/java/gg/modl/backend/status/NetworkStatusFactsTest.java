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
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class NetworkStatusFactsTest {
    private static final String API_KEY = "instance-api-key-that-must-never-be-reported-0123456789";

    @Test
    void theReportCarriesTheAddressButNeverTheConfiguredSecrets() {
        PublicUrls urls = mock(PublicUrls.class);
        when(urls.knownOrigin()).thenReturn(HttpOrigin.parseExact("https://panel.example.com"));
        PanelReachabilityCheck reachability = mock(PanelReachabilityCheck.class);
        when(reachability.latest()).thenReturn(PanelReachability.NOT_CHECKED);
        InstanceProperties properties = new InstanceProperties("https://panel.example.com", "owner@example.com", null, API_KEY,
            "internal-key-0123456789-0123456789", true);
        NetworkStatusFacts facts = new NetworkStatusFacts(urls, properties, reachability, "0.0.0.0", 8080);

        String report = new SupportReportService(List.of(facts), Clock.systemUTC()).report().render();

        assertThat(report).contains("Panel URL: https://panel.example.com", "Listening on: 0.0.0.0:8080")
            .doesNotContain(API_KEY).doesNotContain("internal-key").doesNotContain("owner@example.com");
    }

    @Test
    void aMissingPanelUrlIsAWarning() {
        PublicUrls urls = mock(PublicUrls.class);
        when(urls.knownOrigin()).thenReturn(Optional.empty());
        PanelReachabilityCheck reachability = mock(PanelReachabilityCheck.class);
        when(reachability.latest()).thenReturn(PanelReachability.NOT_CHECKED);
        NetworkStatusFacts facts = new NetworkStatusFacts(urls, new InstanceProperties(null, null, null, null, null, false),
            reachability, "0.0.0.0", 8080);

        assertThat(facts.facts()).anyMatch(fact -> fact.item() == StatusItem.PANEL_URL && fact.level() == StatusLevel.WARNING);
    }
}

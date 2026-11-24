package gg.modl.backend.status.facts;

import gg.modl.backend.infrastructure.web.HttpOrigin;
import gg.modl.backend.instance.InstanceProperties;
import gg.modl.backend.instance.PublicUrls;
import gg.modl.backend.instance.reachability.PanelReachability;
import gg.modl.backend.instance.reachability.PanelReachabilityCheck;
import gg.modl.backend.status.StatusContributor;
import gg.modl.backend.status.StatusFact;
import gg.modl.backend.status.StatusItem;
import gg.modl.backend.status.StatusSection;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class NetworkStatusFacts implements StatusContributor {
    private final PublicUrls publicUrls;
    private final InstanceProperties instanceProperties;
    private final PanelReachabilityCheck reachability;
    private final String bindAddress;
    private final int port;

    public NetworkStatusFacts(PublicUrls publicUrls, InstanceProperties instanceProperties, PanelReachabilityCheck reachability,
                              @Value("${server.address:0.0.0.0}") String bindAddress, @Value("${server.port:8080}") int port) {
        this.publicUrls = publicUrls;
        this.instanceProperties = instanceProperties;
        this.reachability = reachability;
        this.bindAddress = bindAddress;
        this.port = port;
    }

    @Override
    public StatusSection section() {
        return StatusSection.NETWORK;
    }

    @Override
    public List<StatusFact> facts() {
        return List.of(
            panelUrl(),
            StatusItem.LISTEN_PORT.ok(bindAddress + ":" + port),
            StatusItem.PROXY_HEADERS.ok(StatusFormats.yesNo(instanceProperties.trustsProxyHeaders())),
            reachabilityFact(reachability.latest())
        );
    }

    private StatusFact panelUrl() {
        String source = publicUrls.isManagedByConfig() ? " (set by configuration)" : "";
        return publicUrls.knownOrigin()
            .map(HttpOrigin::value)
            .map(origin -> StatusItem.PANEL_URL.ok(origin + source))
            .orElseGet(() -> StatusItem.PANEL_URL.warning("not set"));
    }

    private static StatusFact reachabilityFact(PanelReachability result) {
        String checked = result.checkedAt() == null ? "" : " (checked " + StatusFormats.instant(result.checkedAt()) + ")";
        return switch (result.outcome()) {
            case REACHABLE -> StatusItem.REACHABILITY.ok("yes" + checked);
            case UNREACHABLE -> StatusItem.REACHABILITY.warning("not from this server" + checked);
            case OTHER_SERVICE -> StatusItem.REACHABILITY.problem("another service answers" + checked);
            case URL_UNKNOWN -> StatusItem.REACHABILITY.warning("no panel URL to check");
            case NOT_CHECKED -> StatusItem.REACHABILITY.ok("not checked yet");
        };
    }
}

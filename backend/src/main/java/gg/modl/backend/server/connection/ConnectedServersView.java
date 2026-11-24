package gg.modl.backend.server.connection;

import gg.modl.backend.network.LiveNetworkSession;
import gg.modl.backend.network.LiveNetworkView;
import gg.modl.backend.update.compat.CompatibilityOverview;
import gg.modl.backend.update.compat.PluginCompatibilityPolicy;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class ConnectedServersView {
    private final ConnectedServerRegistry registry;
    private final LiveNetworkView liveNetwork;
    private final CompatibilityOverview compatibilityOverview;
    private final PluginCompatibilityPolicy compatibility;
    private final Clock clock;

    public List<ConnectedServer> connected() {
        Instant now = clock.instant();
        Map<String, ConnectedServer> byInstance = new LinkedHashMap<>();
        registry.connected().forEach(server -> byInstance.put(server.instanceId(), server));
        for (LiveNetworkSession live : liveNetwork.sessions()) {
            ConnectedServer known = byInstance.get(live.instanceId());
            byInstance.put(live.instanceId(), known != null ? known.withHub(live, now) : ConnectedServer.fromHub(live, now));
        }
        byInstance.replaceAll((instanceId, server) -> server.judgedBy(compatibility));
        compatibilityOverview.refusedServers()
            .forEach(refused -> byInstance.putIfAbsent(refused.bootId(), ConnectedServer.refused(refused)));
        return List.copyOf(byInstance.values());
    }

    public String modlVersion() {
        return compatibility.serverVersion();
    }
}

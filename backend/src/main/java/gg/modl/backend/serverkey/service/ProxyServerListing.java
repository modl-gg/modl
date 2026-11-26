package gg.modl.backend.serverkey.service;

import gg.modl.backend.network.LiveNetworkSession;
import gg.modl.backend.network.LiveNetworkView;
import gg.modl.backend.network.NetworkLimits;
import gg.modl.backend.network.ProxyServer;
import gg.modl.backend.serverkey.ServerKey;
import gg.modl.proto.modl.v1.ServerRole;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class ProxyServerListing {
    private static final Comparator<LiveNetworkSession> NEWEST_CORE_FIRST =
        Comparator.comparing(LiveNetworkSession::connectedAt).reversed().thenComparing(LiveNetworkSession::instanceId);

    private final LiveNetworkView liveNetwork;

    public List<ListedProxyServer> list(List<ServerKey> keys, Set<String> connectedKeyIds) {
        Map<String, ServerKey> keysByName = new HashMap<>();
        keys.forEach(key -> keysByName.putIfAbsent(lowerCase(key.name()), key));
        return reported(liveNetwork.sessions()).entrySet().stream()
            .limit(NetworkLimits.MAX_MEMBERS)
            .map(entry -> listed(entry.getValue(), keysByName.get(entry.getKey()), connectedKeyIds))
            .toList();
    }

    private static Map<String, ProxyServer> reported(List<LiveNetworkSession> sessions) {
        Map<String, ProxyServer> byName = new TreeMap<>();
        sessions.stream()
            .filter(session -> session.role() == ServerRole.SERVER_ROLE_CORE)
            .sorted(NEWEST_CORE_FIRST)
            .forEach(core -> core.proxyServers().forEach(server -> byName.putIfAbsent(lowerCase(server.name()), server)));
        return byName;
    }

    private static ListedProxyServer listed(ProxyServer server, @Nullable ServerKey key, Set<String> connectedKeyIds) {
        if (key == null) {
            return new ListedProxyServer(server, ListedProxyServer.State.NO_KEY, null);
        }
        return new ListedProxyServer(server,
            connectedKeyIds.contains(key.id()) ? ListedProxyServer.State.CONNECTED : ListedProxyServer.State.NOT_CONNECTED, key.id());
    }

    private static String lowerCase(String name) {
        return name.toLowerCase(Locale.ROOT);
    }
}

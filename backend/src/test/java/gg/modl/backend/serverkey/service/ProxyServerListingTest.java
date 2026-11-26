package gg.modl.backend.serverkey.service;

import static org.assertj.core.api.Assertions.assertThat;

import gg.modl.backend.network.LiveNetworkSession;
import gg.modl.backend.network.LiveNetworkView;
import gg.modl.backend.network.NetworkLimits;
import gg.modl.backend.network.ProxyServer;
import gg.modl.backend.serverkey.ServerKey;
import gg.modl.backend.serverkey.ServerKeyScope;
import gg.modl.proto.modl.v1.ServerRole;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class ProxyServerListingTest {
    private static final Instant START = Instant.parse("2026-11-24T12:00:00Z");

    private final List<LiveNetworkSession> live = new ArrayList<>();
    private final ProxyServerListing listing = new ProxyServerListing(new LiveNetworkView() {
        @Override
        public List<LiveNetworkSession> sessions() {
            return live;
        }

        @Override
        public Set<String> connectedKeyIds() {
            return Set.of();
        }
    });

    @Test
    void eachServerOfTheProxyIsListedWithoutAKeyWithAKeyOrConnected() {
        live.add(core("proxy", START, new ProxyServer("survival", "10.0.0.3:25565"), new ProxyServer("lobby", "10.0.0.2:25565"),
            new ProxyServer("minigames", "10.0.0.4:25565")));
        List<ListedProxyServer> listed = listing.list(List.of(key("k-lobby", "lobby"), key("k-survival", "Survival")), Set.of("k-lobby"));

        assertThat(listed).extracting(entry -> entry.server().name()).containsExactly("lobby", "minigames", "survival");
        assertThat(listed).extracting(ListedProxyServer::state).containsExactly(
            ListedProxyServer.State.CONNECTED, ListedProxyServer.State.NO_KEY, ListedProxyServer.State.NOT_CONNECTED);
        assertThat(listed).extracting(ListedProxyServer::keyId).containsExactly("k-lobby", null, "k-survival");
        assertThat(listed.get(0).server().address()).isEqualTo("10.0.0.2:25565");
    }

    @Test
    void aNameReportedByTwoProxiesIsListedOnceFromTheNewerProxy() {
        live.add(core("older", START, new ProxyServer("lobby", "10.0.0.2:25565")));
        live.add(core("newer", START.plusSeconds(60), new ProxyServer("LOBBY", "10.0.0.9:25565")));

        assertThat(listing.list(List.of(), Set.of())).singleElement()
            .satisfies(entry -> assertThat(entry.server()).isEqualTo(new ProxyServer("LOBBY", "10.0.0.9:25565")));
    }

    @Test
    void membersReportNoServersAndTheListingIsBounded() {
        live.add(new LiveNetworkSession("member", "lobby", ServerRole.SERVER_ROLE_MEMBER, "lobby", "3.0.0", 0, START,
            List.of(new ProxyServer("spoofed", "1.2.3.4:25565")), null));
        assertThat(listing.list(List.of(), Set.of())).isEmpty();

        live.add(core("a", START, IntStream.range(0, NetworkLimits.MAX_MEMBERS)
            .mapToObj(index -> new ProxyServer("a" + index, "")).toArray(ProxyServer[]::new)));
        live.add(core("b", START.plusSeconds(1), IntStream.range(0, NetworkLimits.MAX_MEMBERS)
            .mapToObj(index -> new ProxyServer("b" + index, "")).toArray(ProxyServer[]::new)));
        assertThat(listing.list(List.of(), Set.of())).hasSize(NetworkLimits.MAX_MEMBERS);
    }

    private static LiveNetworkSession core(String instanceId, Instant connectedAt, ProxyServer... servers) {
        return new LiveNetworkSession(instanceId, "Proxy", ServerRole.SERVER_ROLE_CORE, null, "3.0.0", 0, connectedAt, List.of(servers), null);
    }

    private static ServerKey key(String id, String name) {
        return new ServerKey(id, name, ServerKeyScope.BEHIND_PROXY, false, "h".repeat(64), "abcdefgh", null, START, null);
    }
}

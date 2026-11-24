package gg.modl.backend.server.connection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import gg.modl.backend.network.LiveNetworkSession;
import gg.modl.backend.network.LiveNetworkView;
import gg.modl.backend.support.MutableClock;
import gg.modl.proto.modl.v1.ServerRole;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import gg.modl.backend.infrastructure.logging.OperatorNotice;
import gg.modl.backend.update.ReleaseBuild;
import gg.modl.backend.update.compat.CompatibilityOverview;
import gg.modl.backend.update.compat.PluginCompatibilityPolicy;
import gg.modl.backend.update.compat.RefusedServers;
import gg.modl.update.ReleaseVersion;
import gg.modl.update.VersionCompatibility;

class ConnectedServersViewTest {
    private static final Instant START = Instant.parse("2026-11-22T12:00:00Z");

    private final MutableClock clock = new MutableClock(START);
    private final ConnectedServerRegistry registry = new ConnectedServerRegistry(clock);
    private final List<LiveNetworkSession> live = new ArrayList<>();
    private final LiveNetworkView view = new LiveNetworkView() {
        @Override
        public List<LiveNetworkSession> sessions() {
            return live;
        }

        @Override
        public Set<String> connectedKeyIds() {
            return Set.of();
        }
    };
    private final RefusedServers refusedServers = mock(RefusedServers.class);
    private final ConnectedServersView servers = new ConnectedServersView(registry, view, new CompatibilityOverview(refusedServers),
        new PluginCompatibilityPolicy(ReleaseBuild.of(ReleaseVersion.parse("3.0.1"), ReleaseVersion.parse("3.0.0")), refusedServers,
            mock(OperatorNotice.class)), clock);

    @Test
    void aHubSessionMergesIntoItsStartupRowAndItsLivenessWins() {
        registry.recordStartup("lobby", ServerStartupReport.of("3.0.0", "SPIGOT", "1.21.11", ServerRole.SERVER_ROLE_MEMBER, "lobby"));
        clock.advance(Duration.ofMinutes(9));
        live.add(new LiveNetworkSession("lobby", "lobby", ServerRole.SERVER_ROLE_MEMBER, "lobby", "3.0.1", 12, START));

        assertThat(servers.connected()).singleElement().satisfies(server -> {
            assertThat(server.platform()).isEqualTo("SPIGOT");
            assertThat(server.hubConnected()).isTrue();
            assertThat(server.onlinePlayers()).isEqualTo(12);
            assertThat(server.pluginVersion()).isEqualTo("3.0.1");
            assertThat(server.lastSeen()).isEqualTo(clock.instant());
            assertThat(server.compatibility()).isEqualTo(VersionCompatibility.COMPATIBLE);
        });
    }

    @Test
    void aHubSessionStaysListedAfterItsStartupRowExpires() {
        registry.recordStartup("lobby", ServerStartupReport.of("3.0.0", "SPIGOT", "1.21.11", ServerRole.SERVER_ROLE_MEMBER, "lobby"));
        live.add(new LiveNetworkSession("lobby", "lobby", ServerRole.SERVER_ROLE_MEMBER, "lobby", "3.0.0", 3, START));
        clock.advance(ConnectedServerRegistry.EXPIRY.plusMinutes(1));

        assertThat(servers.connected()).singleElement().satisfies(server -> {
            assertThat(server.instanceId()).isEqualTo("lobby");
            assertThat(server.role()).isEqualTo(ServerRole.SERVER_ROLE_MEMBER);
            assertThat(server.keyName()).isEqualTo("lobby");
            assertThat(server.hubConnected()).isTrue();
        });
    }

    @Test
    void refusedServersAreListedWithTheirVerdictAndAnOldPluginIsFlagged() {
        registry.recordStartup("old", ServerStartupReport.of("2.9.0", "SPIGOT", "1.21.11", ServerRole.SERVER_ROLE_CORE, null));
        when(refusedServers.current()).thenReturn(List.of(new RefusedServers.RefusedServer("boot-1", "VELOCITY", "3.1.0",
            VersionCompatibility.SERVER_OLDER, START)));

        assertThat(servers.connected()).extracting(ConnectedServer::instanceId, ConnectedServer::compatibility).containsExactly(
            tuple("old", VersionCompatibility.PLUGIN_OLDER), tuple("boot-1", VersionCompatibility.SERVER_OLDER));
        assertThat(servers.modlVersion()).isEqualTo("3.0.1");
    }

    @Test
    void aCoreKeepsItsSyncedPlayerCount() {
        registry.recordStartup("proxy", ServerStartupReport.of("3.0.0", "VELOCITY", "3.4.0", ServerRole.SERVER_ROLE_CORE, null));
        registry.recordHeartbeat(new ServerHeartbeat("proxy", "Network", 40));
        live.add(new LiveNetworkSession("proxy", "Network", ServerRole.SERVER_ROLE_CORE, null, "3.0.0", 0, START));

        assertThat(servers.connected()).singleElement().satisfies(server -> {
            assertThat(server.onlinePlayers()).isEqualTo(40);
            assertThat(server.hubConnected()).isTrue();
        });
    }
}

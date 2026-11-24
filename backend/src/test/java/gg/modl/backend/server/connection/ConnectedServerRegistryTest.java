package gg.modl.backend.server.connection;

import static org.assertj.core.api.Assertions.assertThat;

import gg.modl.backend.support.MutableClock;
import gg.modl.proto.modl.v1.ServerRole;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class ConnectedServerRegistryTest {
    private static final Instant START = Instant.parse("2026-11-21T12:00:00Z");

    private final MutableClock clock = new MutableClock(START);
    private final ConnectedServerRegistry registry = new ConnectedServerRegistry(clock);

    @Test
    void aStartedServerIsListedAndItsSyncsFillInNameAndPlayers() {
        registry.recordStartup("proxy", startup("3.0.0", "VELOCITY", "4.3.1"));
        clock.advance(Duration.ofSeconds(5));
        registry.recordHeartbeat(new ServerHeartbeat("proxy", "Network", 3));

        assertThat(registry.connected()).singleElement().satisfies(server -> {
            assertThat(server.platform()).isEqualTo("VELOCITY");
            assertThat(server.pluginVersion()).isEqualTo("3.0.0");
            assertThat(server.name()).isEqualTo("Network");
            assertThat(server.onlinePlayers()).isEqualTo(3);
            assertThat(server.role()).isEqualTo(ServerRole.SERVER_ROLE_CORE);
            assertThat(server.hubConnected()).isFalse();
            assertThat(server.lastSeen()).isEqualTo(START.plusSeconds(5));
        });
    }

    @Test
    void aSyncFromAServerStartedBeforeTheBackendStillRegistersIt() {
        registry.recordHeartbeat(new ServerHeartbeat("paper", "Survival", 0));

        assertThat(registry.connected()).extracting(ConnectedServer::name).containsExactly("Survival");
    }

    @Test
    void heartbeatsWithoutAnInstanceIdAreIgnored() {
        registry.recordHeartbeat(new ServerHeartbeat(null, "Unknown", 1));

        assertThat(registry.connected()).isEmpty();
    }

    @Test
    void serversDisappearTenMinutesAfterTheyWereLastSeen() {
        registry.recordStartup("paper", startup("3.0.0", "SPIGOT", "1.21.11"));
        clock.advance(ConnectedServerRegistry.EXPIRY.minusSeconds(1));
        assertThat(registry.connected()).hasSize(1);

        clock.advance(Duration.ofSeconds(2));

        assertThat(registry.connected()).isEmpty();
    }

    @Test
    void theRegistryIsCappedByDroppingTheServerSeenLongestAgo() {
        for (int index = 0; index < ConnectedServerRegistry.CAPACITY; index++) {
            registry.recordStartup("server-" + index, startup("3.0.0", "SPIGOT", "1.21.11"));
            clock.advance(Duration.ofMillis(1));
        }

        registry.recordStartup("newcomer", startup("3.0.0", "SPIGOT", "1.21.11"));

        assertThat(registry.connected()).hasSize(ConnectedServerRegistry.CAPACITY)
            .extracting(ConnectedServer::instanceId)
            .doesNotContain("server-0")
            .contains("newcomer");
    }

    @Test
    void reportedTextIsTrimmedAndBounded() {
        String longName = "x".repeat(500);
        registry.recordStartup("paper", startup(" 3.0.0 ", null, longName));

        assertThat(registry.connected()).singleElement().satisfies(server -> {
            assertThat(server.pluginVersion()).isEqualTo("3.0.0");
            assertThat(server.platform()).isEmpty();
            assertThat(server.serverVersion()).hasSize(ReportedText.MAX_LENGTH);
        });
    }

    @Test
    void aMemberStartupIsNamedByItsServerKey() {
        registry.recordStartup("lobby-instance", ServerStartupReport.of("3.0.0", "SPIGOT", "1.21.11",
            ServerRole.SERVER_ROLE_MEMBER, "lobby"));

        assertThat(registry.connected()).singleElement().satisfies(server -> {
            assertThat(server.name()).isEqualTo("lobby");
            assertThat(server.keyName()).isEqualTo("lobby");
            assertThat(server.role()).isEqualTo(ServerRole.SERVER_ROLE_MEMBER);
        });
    }

    private static ServerStartupReport startup(String pluginVersion, String platform, String serverVersion) {
        return ServerStartupReport.of(pluginVersion, platform, serverVersion, ServerRole.SERVER_ROLE_CORE, null);
    }
}

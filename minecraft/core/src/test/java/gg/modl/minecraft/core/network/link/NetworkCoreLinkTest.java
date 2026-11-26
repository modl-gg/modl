package gg.modl.minecraft.core.network.link;

import gg.modl.minecraft.core.network.NetworkAvailability;
import gg.modl.minecraft.core.network.ProxyBackend;
import gg.modl.minecraft.core.network.SwitchableNetworkAvailability;
import gg.modl.minecraft.core.realtime.RealtimeConnection;
import gg.modl.minecraft.core.support.FakePlatform;
import gg.modl.minecraft.core.support.RecordingPluginLogger;
import gg.modl.minecraft.core.support.TestConnections;
import gg.modl.proto.modl.v1.NetworkMember;
import gg.modl.proto.modl.v1.NetworkRosterEvent;
import gg.modl.proto.modl.v1.ServerRole;
import gg.modl.proto.modl.v1.Topic;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NetworkCoreLinkTest {
    private final RecordingPluginLogger logger = new RecordingPluginLogger();
    private final SwitchableNetworkAvailability availability = new SwitchableNetworkAvailability(NetworkAvailability.NOWHERE);
    private final FakePlatform platform = new FakePlatform() {
        @Override
        public List<ProxyBackend> proxyBackends() {
            return Arrays.asList(new ProxyBackend("lobby", "10.0.0.2:25565"), new ProxyBackend("survival", "10.0.0.3:25565"));
        }
    };
    private final NetworkCoreLink link = NetworkCoreLink.builder()
            .connection(new RealtimeConnection(TestConnections.to("http://127.0.0.1:1", "key"), 1,
                    Collections.singleton(Topic.TOPIC_NETWORK_ROSTER), () -> "Proxy", null, logger, false))
            .availability(availability)
            .platform(platform)
            .logger(logger)
            .build();

    @Test
    void theRosterNamesWhereTheNetworkCanEnforceAndWarnsOnceAboutUnknownNames() {
        link.roster().replace(NetworkRosterEvent.getDefaultInstance());
        availability.use(link.roster());

        link.onRoster(roster("Lobby", "creative"));
        link.onRoster(roster("Lobby", "creative"));

        assertTrue(availability.isEnforcedOn("lobby"));
        assertFalse(availability.isEnforcedOn("survival"));
        assertEquals(1, logger.warnings().size());
        assertTrue(logger.warnings().get(0).startsWith("[network] creative is not a server name in the proxy config"));
    }

    @Test
    void proxiesAndUnnamedEntriesAreNotMembers() {
        link.onRoster(NetworkRosterEvent.newBuilder()
                .addMembers(NetworkMember.newBuilder().setName("Proxy").setRole(ServerRole.SERVER_ROLE_CORE))
                .addMembers(NetworkMember.newBuilder().setRole(ServerRole.SERVER_ROLE_MEMBER))
                .build());

        assertFalse(link.roster().hasMembers());
    }

    private static NetworkRosterEvent roster(String... names) {
        return NetworkStatWipeExecutorTest.roster(names);
    }
}

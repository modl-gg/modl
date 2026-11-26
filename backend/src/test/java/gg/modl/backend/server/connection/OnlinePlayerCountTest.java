package gg.modl.backend.server.connection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import gg.modl.backend.support.MutableClock;
import gg.modl.proto.modl.v1.ServerRole;
import gg.modl.update.VersionCompatibility;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class OnlinePlayerCountTest {
    private static final Instant START = Instant.parse("2026-11-24T12:00:00Z");

    private final MutableClock clock = new MutableClock(START);
    private final ConnectedServersView servers = mock(ConnectedServersView.class);
    private final OnlinePlayerCount count = new OnlinePlayerCount(servers, clock);

    @Test
    void onlyCoresAreCountedSoPlayersBehindAProxyAreNotCountedTwice() {
        when(servers.connected()).thenReturn(List.of(
            server("proxy", ServerRole.SERVER_ROLE_CORE, 30),
            server("lobby", ServerRole.SERVER_ROLE_MEMBER, 20),
            server("survival", ServerRole.SERVER_ROLE_MEMBER, 10),
            server("standalone", ServerRole.SERVER_ROLE_CORE, 5),
            server("refused", ServerRole.SERVER_ROLE_UNSPECIFIED, 0)));

        assertThat(count.total()).isEqualTo(35);
    }

    @Test
    void aCoreWithoutALiveConnectionIsNotCountedSoARestartDoesNotCountPlayersTwice() {
        when(servers.connected()).thenReturn(List.of(
            server("survival-before-restart", ServerRole.SERVER_ROLE_CORE, 50, false),
            server("survival", ServerRole.SERVER_ROLE_CORE, 50)));

        assertThat(count.total()).isEqualTo(50);
    }

    @Test
    void theCountIsCachedForAFewSeconds() {
        when(servers.connected())
            .thenReturn(List.of(server("proxy", ServerRole.SERVER_ROLE_CORE, 3)))
            .thenReturn(List.of(server("proxy", ServerRole.SERVER_ROLE_CORE, 4)));

        assertThat(count.total()).isEqualTo(3);
        clock.advance(OnlinePlayerCount.CACHE_TIME.minus(Duration.ofMillis(1)));
        assertThat(count.total()).isEqualTo(3);
        clock.advance(Duration.ofMillis(1));
        assertThat(count.total()).isEqualTo(4);
        verify(servers, times(2)).connected();
    }

    @Test
    void noConnectedServerMeansNobodyIsOnline() {
        when(servers.connected()).thenReturn(List.of());

        assertThat(count.total()).isZero();
    }

    private static ConnectedServer server(String name, ServerRole role, int players) {
        return server(name, role, players, true);
    }

    private static ConnectedServer server(String name, ServerRole role, int players, boolean live) {
        return new ConnectedServer(name, name, "", "3.0.0", "", players, START, role, null, live, VersionCompatibility.COMPATIBLE, null);
    }
}

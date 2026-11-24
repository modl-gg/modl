package gg.modl.backend.realtime.schedule;

import gg.modl.backend.support.TestServers;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import gg.modl.backend.auth.session.SessionService;
import gg.modl.backend.realtime.auth.RealtimePrincipal;
import gg.modl.backend.realtime.auth.RealtimeTopicAuthorizer;
import gg.modl.backend.realtime.config.RealtimeProperties;
import gg.modl.backend.realtime.metrics.RealtimeMetrics;
import gg.modl.backend.realtime.state.RealtimeConnectionRegistry;
import gg.modl.backend.realtime.state.RealtimeConnectionState;
import gg.modl.backend.realtime.transport.RealtimeSessionOperations;
import gg.modl.backend.server.data.Server;
import gg.modl.proto.modl.v1.Topic;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;

class RealtimePanelAuthorizationSweeperTest {
    private static final String LIVE_SESSION = "live-session";

    private final SessionService sessionService = mock(SessionService.class);
    private final RealtimeSessionOperations sessionOperations = mock(RealtimeSessionOperations.class);

    @Test
    void revokesOnlyTopicsThatLostAuthorization() {
        RealtimeProperties properties = enabledProperties();
        RealtimeConnectionRegistry registry = new RealtimeConnectionRegistry(properties);
        RealtimeTopicAuthorizer authorizer = mock(RealtimeTopicAuthorizer.class);
        RealtimePanelAuthorizationSweeper sweeper = sweeper(properties, registry, authorizer);

        RealtimePrincipal principal = RealtimePrincipal.panel(server(), "staff@example.com", LIVE_SESSION);
        RealtimeConnectionState state = registry.register(session("panel"));
        state.authenticate(principal);
        state.subscribe(Topic.TOPIC_PANEL_TICKETS);
        state.subscribe(Topic.TOPIC_PANEL_DASHBOARD);

        when(authorizer.canSubscribe(principal, Topic.TOPIC_PANEL_TICKETS)).thenReturn(true);
        when(authorizer.canSubscribe(principal, Topic.TOPIC_PANEL_DASHBOARD)).thenReturn(false);

        sweeper.enforcePanelAuthorization();

        assertTrue(state.isSubscribedTo(Topic.TOPIC_PANEL_TICKETS));
        assertFalse(state.isSubscribedTo(Topic.TOPIC_PANEL_DASHBOARD));
    }

    @Test
    void closesPanelConnectionsWhoseSessionEnded() {
        RealtimeProperties properties = enabledProperties();
        RealtimeConnectionRegistry registry = new RealtimeConnectionRegistry(properties);
        RealtimeTopicAuthorizer authorizer = mock(RealtimeTopicAuthorizer.class);
        RealtimePanelAuthorizationSweeper sweeper = sweeper(properties, registry, authorizer);

        RealtimeConnectionState live = registry.register(session("live"));
        live.authenticate(RealtimePrincipal.panel(server(), "staff@example.com", LIVE_SESSION));
        RealtimeConnectionState ended = registry.register(session("ended"));
        ended.authenticate(RealtimePrincipal.panel(server(), "staff@example.com", "logged-out-session"));
        when(sessionService.activeSessionIds(any(Server.class), eq(Set.of(LIVE_SESSION, "logged-out-session"))))
            .thenReturn(Set.of(LIVE_SESSION));

        sweeper.enforcePanelAuthorization();

        verify(sessionOperations).requestClose(any(WebSocketSession.class), eq(ended), any(CloseStatus.class), eq("panel_session_ended"));
        verify(sessionOperations, never()).requestClose(any(), eq(live), any(), any());
    }

    @Test
    void leavesMinecraftConnectionsUntouched() {
        RealtimeProperties properties = enabledProperties();
        RealtimeConnectionRegistry registry = new RealtimeConnectionRegistry(properties);
        RealtimeTopicAuthorizer authorizer = mock(RealtimeTopicAuthorizer.class);
        RealtimePanelAuthorizationSweeper sweeper = sweeper(properties, registry, authorizer);

        RealtimeConnectionState state = registry.register(session("minecraft"));
        state.authenticate(RealtimePrincipal.minecraft(server(), "instance-1"));
        state.subscribe(Topic.TOPIC_MINECRAFT_PUNISHMENTS);

        sweeper.enforcePanelAuthorization();

        assertTrue(state.isSubscribedTo(Topic.TOPIC_MINECRAFT_PUNISHMENTS));
        verifyNoInteractions(authorizer);
    }

    @Test
    void doesNothingWhenDisabled() {
        RealtimeProperties properties = new RealtimeProperties();
        properties.setEnabled(false);
        RealtimeConnectionRegistry registry = new RealtimeConnectionRegistry(properties);
        RealtimeTopicAuthorizer authorizer = mock(RealtimeTopicAuthorizer.class);
        RealtimePanelAuthorizationSweeper sweeper = sweeper(properties, registry, authorizer);

        RealtimeConnectionState state = registry.register(session("panel-disabled"));
        state.authenticate(RealtimePrincipal.panel(server(), "staff@example.com", LIVE_SESSION));
        state.subscribe(Topic.TOPIC_PANEL_TICKETS);

        sweeper.enforcePanelAuthorization();

        assertTrue(state.isSubscribedTo(Topic.TOPIC_PANEL_TICKETS));
        verifyNoInteractions(authorizer);
    }

    @Test
    void skipsUnauthenticatedConnections() {
        RealtimeProperties properties = enabledProperties();
        RealtimeConnectionRegistry registry = new RealtimeConnectionRegistry(properties);
        RealtimeTopicAuthorizer authorizer = mock(RealtimeTopicAuthorizer.class);
        RealtimePanelAuthorizationSweeper sweeper = sweeper(properties, registry, authorizer);

        registry.register(session("pending"));

        sweeper.enforcePanelAuthorization();

        verifyNoInteractions(authorizer);
    }

    private RealtimePanelAuthorizationSweeper sweeper(RealtimeProperties properties, RealtimeConnectionRegistry registry,
                                                      RealtimeTopicAuthorizer authorizer) {
        when(sessionService.activeSessionIds(any(Server.class), any())).thenReturn(Set.of(LIVE_SESSION));
        return new RealtimePanelAuthorizationSweeper(properties, registry, authorizer,
            new RealtimeMetrics(new SimpleMeterRegistry()), sessionService, sessionOperations);
    }

    private RealtimeProperties enabledProperties() {
        RealtimeProperties properties = new RealtimeProperties();
        properties.setEnabled(true);
        return properties;
    }

    private Server server() {
        Server server = TestServers.server("server", "admin@example.com");
        server.setId("server-id");
        return server;
    }

    private WebSocketSession session(String id) {
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn(id);
        when(session.isOpen()).thenReturn(true);
        when(session.getAttributes()).thenReturn(new ConcurrentHashMap<>());
        return session;
    }
}

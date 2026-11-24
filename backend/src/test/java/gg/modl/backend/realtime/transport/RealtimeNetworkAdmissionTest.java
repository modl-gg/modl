package gg.modl.backend.realtime.transport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import gg.modl.backend.instance.MinecraftCredential;
import gg.modl.backend.network.NetworkHub;
import gg.modl.backend.realtime.auth.RealtimeAuthenticator;
import gg.modl.backend.realtime.auth.RealtimePrincipal;
import gg.modl.backend.realtime.auth.RealtimeTopicAuthorizer;
import gg.modl.backend.realtime.config.RealtimeProperties;
import gg.modl.backend.realtime.lifecycle.RealtimeConnectionCleanup;
import gg.modl.backend.realtime.metrics.RealtimeMetrics;
import gg.modl.backend.realtime.rate.PluginInboundBudget;
import gg.modl.backend.realtime.rate.RealtimeMessageRateLimiter;
import gg.modl.backend.realtime.rate.RealtimeUnauthenticatedConnectionLimiter;
import gg.modl.backend.realtime.state.RealtimeConnectionRegistry;
import gg.modl.backend.realtime.state.RealtimeConnectionState;
import gg.modl.backend.serverkey.ServerKeyScope;
import gg.modl.backend.support.TestServers;
import gg.modl.proto.modl.v1.ClientHello;
import gg.modl.proto.modl.v1.ClientKind;
import gg.modl.proto.modl.v1.RealtimeCloseCode;
import gg.modl.proto.modl.v1.RealtimeEnvelope;
import gg.modl.proto.modl.v1.ServerRole;
import gg.modl.proto.modl.v1.Topic;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketMessage;
import org.springframework.web.socket.WebSocketSession;

class RealtimeNetworkAdmissionTest {
    private final RealtimeProperties properties = new RealtimeProperties();
    private final RealtimeConnectionRegistry registry = new RealtimeConnectionRegistry(properties);
    private final RealtimeAuthenticator authenticator = mock(RealtimeAuthenticator.class);
    private final RealtimeTopicAuthorizer topicAuthorizer = mock(RealtimeTopicAuthorizer.class);
    private final NetworkHub hub = mock(NetworkHub.class);
    private final RealtimePrincipal member = RealtimePrincipal.minecraft(TestServers.server("Server", "owner@example.com"),
        "lobby-instance", new MinecraftCredential(ServerKeyScope.BEHIND_PROXY, "k1", "lobby"), ServerRole.SERVER_ROLE_MEMBER,
        "lobby", "3.0.0");
    private RealtimeWebSocketHandler handler;

    @BeforeEach
    void setUp() throws Exception {
        properties.setEnabled(true);
        RealtimeMessageRateLimiter rateLimiter = new RealtimeMessageRateLimiter(properties);
        RealtimeMetrics metrics = new RealtimeMetrics(new SimpleMeterRegistry());
        RealtimeConnectionCleanup cleanup = new RealtimeConnectionCleanup(registry, rateLimiter, new PluginInboundBudget(), metrics,
            event -> { });
        handler = new RealtimeWebSocketHandler(properties, new RealtimeCodec(properties), authenticator, topicAuthorizer, registry,
            rateLimiter, cleanup, metrics,
            new RealtimeSessionOperations(registry, cleanup, metrics, new RealtimeOutboundExecutor(Runnable::run)),
            new RealtimeUnauthenticatedConnectionLimiter(properties), new PluginInboundBudget(), hub);
        when(authenticator.authenticate(any(), any(), any())).thenReturn(member);
        when(topicAuthorizer.canSubscribe(any(), any())).thenReturn(true);
    }

    @Test
    void aRefusedMemberIsClosedBeforeItIsGreetedSubscribedOrRegistered() throws Exception {
        WebSocketSession socket = socket("full");
        RealtimeConnectionState state = registry.register(socket);
        when(hub.admit(same(socket), same(state), same(member))).thenReturn(NetworkHub.Admission.NETWORK_FULL);

        handler.handleBinaryMessage(socket, hello());

        assertThat(state.isAuthenticated()).isFalse();
        assertThat(state.isSubscribedTo(Topic.TOPIC_NETWORK_MODERATION)).isFalse();
        assertThat(payloadsSentTo(socket)).doesNotContain(RealtimeEnvelope.PayloadCase.SERVER_HELLO);
        ArgumentCaptor<CloseStatus> closed = ArgumentCaptor.forClass(CloseStatus.class);
        verify(socket).close(closed.capture());
        assertThat(closed.getValue().getCode()).isEqualTo(RealtimeCloseCode.REALTIME_CLOSE_CODE_OVERLOADED.getNumber());
        verify(hub, never()).welcome(any());
    }

    @Test
    void anAdmittedMemberIsWelcomedOnlyAfterItsServerHello() throws Exception {
        WebSocketSession socket = socket("admitted");
        RealtimeConnectionState state = registry.register(socket);
        when(hub.admit(same(socket), same(state), same(member))).thenAnswer(call -> {
            assertThat(state.isAuthenticated()).isFalse();
            return NetworkHub.Admission.ADMITTED;
        });

        handler.handleBinaryMessage(socket, hello());

        assertThat(state.isAuthenticated()).isTrue();
        assertThat(payloadsSentTo(socket)).containsExactly(RealtimeEnvelope.PayloadCase.SERVER_HELLO);
        verify(hub).welcome(state);
    }

    private BinaryMessage hello() {
        return new BinaryMessage(RealtimeEnvelope.newBuilder()
            .setProtocolVersion(properties.getProtocolVersion())
            .setClientHello(ClientHello.newBuilder()
                .setClientKind(ClientKind.CLIENT_KIND_MINECRAFT_PLUGIN)
                .setProtocolVersion(properties.getProtocolVersion())
                .setServerRole(ServerRole.SERVER_ROLE_MEMBER)
                .addSupportedTopics(Topic.TOPIC_NETWORK_MODERATION))
            .build()
            .toByteArray());
    }

    private static List<RealtimeEnvelope.PayloadCase> payloadsSentTo(WebSocketSession socket) throws Exception {
        ArgumentCaptor<WebSocketMessage<?>> sent = ArgumentCaptor.captor();
        verify(socket, atLeast(0)).sendMessage(sent.capture());
        List<RealtimeEnvelope.PayloadCase> payloads = new ArrayList<>();
        for (WebSocketMessage<?> message : sent.getAllValues()) {
            if (message instanceof BinaryMessage binary) {
                payloads.add(RealtimeEnvelope.parseFrom(binary.getPayload().duplicate()).getPayloadCase());
            }
        }
        return payloads;
    }

    private static WebSocketSession socket(String id) {
        WebSocketSession socket = mock(WebSocketSession.class);
        when(socket.getId()).thenReturn(id);
        when(socket.isOpen()).thenReturn(true);
        when(socket.getAttributes()).thenReturn(new ConcurrentHashMap<>());
        return socket;
    }
}

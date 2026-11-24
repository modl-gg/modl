package gg.modl.backend.realtime.transport;

import static org.assertj.core.api.Assertions.assertThat;

import gg.modl.backend.realtime.auth.RealtimePrincipal;
import gg.modl.backend.realtime.config.RealtimeProperties;
import gg.modl.backend.realtime.dispatch.InProcessRealtimeEventDispatcher;
import gg.modl.backend.realtime.dispatch.RealtimeDispatchExecutor;
import gg.modl.backend.realtime.dispatch.RealtimeOutboundEvent;
import gg.modl.backend.realtime.dispatch.RealtimeTopicPayloadValidator;
import gg.modl.backend.realtime.lifecycle.RealtimeConnectionCleanup;
import gg.modl.backend.realtime.metrics.RealtimeMetrics;
import gg.modl.backend.realtime.rate.PluginInboundBudget;
import gg.modl.backend.realtime.rate.RealtimeMessageRateLimiter;
import gg.modl.backend.realtime.state.RealtimeConnectionRegistry;
import gg.modl.backend.realtime.state.RealtimeConnectionState;
import gg.modl.backend.server.data.Server;
import gg.modl.backend.support.TestServers;
import gg.modl.proto.modl.v1.PunishmentNotificationEvent;
import gg.modl.proto.modl.v1.RealtimeEnvelope;
import gg.modl.proto.modl.v1.Topic;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.websocket.DeploymentException;
import jakarta.websocket.Endpoint;
import jakarta.websocket.server.ServerContainer;
import jakarta.websocket.server.ServerEndpointConfig;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.catalina.Context;
import org.apache.catalina.startup.Tomcat;
import org.apache.tomcat.websocket.server.WsSci;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpHeaders;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.adapter.standard.StandardWebSocketHandlerAdapter;
import org.springframework.web.socket.adapter.standard.StandardWebSocketSession;
import org.springframework.web.socket.handler.BinaryWebSocketHandler;

class RealtimeStoppedReaderSocketTest {
    private static final String SERVER_ID = "instance";
    private static final String PATH = "/realtime";
    private static final int EVENTS = 300;
    private static final int EVENT_PAYLOAD_CHARACTERS = 30_000;
    private static final long WAIT_SECONDS = 20;
    private static final int BURST = 10;

    @TempDir
    Path baseDirectory;

    private final RealtimeOutboundExecutor outbound = new RealtimeOutboundExecutor(new RealtimeProperties());
    private final List<WebSocket> clients = new CopyOnWriteArrayList<>();
    private Tomcat tomcat;

    @AfterEach
    void stop() throws Exception {
        clients.forEach(WebSocket::abort);
        if (tomcat != null) {
            tomcat.stop();
            tomcat.destroy();
        }
        outbound.shutdown();
    }

    @Test
    void aRealClientThatStopsReadingIsClosedWhileAReadingClientReceivesEveryEvent() throws Exception {
        RealtimeProperties properties = new RealtimeProperties();
        properties.setOutboxMaxMessages(EVENTS);
        properties.setOutboxMaxBytes(1024 * 1024);
        RealtimeConnectionRegistry registry = new RealtimeConnectionRegistry(properties);
        RealtimeMetrics metrics = new RealtimeMetrics(new SimpleMeterRegistry());
        RealtimeConnectionCleanup cleanup = new RealtimeConnectionCleanup(registry, new RealtimeMessageRateLimiter(properties), new PluginInboundBudget(), metrics, event -> { });
        RealtimeSessionOperations operations = new RealtimeSessionOperations(registry, cleanup, metrics, outbound);
        InProcessRealtimeEventDispatcher dispatcher = new InProcessRealtimeEventDispatcher(registry, new RealtimeCodec(properties),
            metrics, operations, new RealtimeTopicPayloadValidator(), inlineDispatch(properties));
        List<RealtimeConnectionState> connections = new CopyOnWriteArrayList<>();
        CountDownLatch stoppedReaderConnected = new CountDownLatch(1);
        CountDownLatch readerConnected = new CountDownLatch(2);
        int port = start(new BinaryWebSocketHandler() {
            @Override
            public void afterConnectionEstablished(WebSocketSession session) {
                connections.add(subscribe(registry, session));
                stoppedReaderConnected.countDown();
                readerConnected.countDown();
            }

            @Override
            public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
                cleanup.unregister(session, status);
            }
        });
        URI uri = URI.create("ws://127.0.0.1:" + port + PATH);
        HttpClient http = HttpClient.newHttpClient();
        clients.add(http.newWebSocketBuilder().buildAsync(uri, new StoppedReader()).get(WAIT_SECONDS, TimeUnit.SECONDS));
        assertThat(stoppedReaderConnected.await(WAIT_SECONDS, TimeUnit.SECONDS)).isTrue();
        Reader reader = new Reader();
        clients.add(http.newWebSocketBuilder().buildAsync(uri, reader).get(WAIT_SECONDS, TimeUnit.SECONDS));
        assertThat(readerConnected.await(WAIT_SECONDS, TimeUnit.SECONDS)).isTrue();

        long publishingNanos = 0;
        for (int index = 0; index < EVENTS; index++) {
            long started = System.nanoTime();
            dispatcher.publish(new RealtimeOutboundEvent(SERVER_ID, Topic.TOPIC_MINECRAFT_PUNISHMENTS, event(index)));
            publishingNanos += System.nanoTime() - started;
            if ((index + 1) % BURST == 0) {
                assertThat(reader.awaitAtLeast(index + 1)).isTrue();
            }
        }

        assertThat(Duration.ofNanos(publishingNanos)).isLessThan(Duration.ofSeconds(5));
        assertThat(reader.awaitAtLeast(EVENTS)).isTrue();
        RealtimeConnectionState stoppedReader = connections.getFirst();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS);
        while (stoppedReader.getTerminalSince() == null && System.nanoTime() < deadline) {
            TimeUnit.MILLISECONDS.sleep(20);
        }
        assertThat(stoppedReader.getTerminalSince()).isNotNull();
        assertThat(stoppedReader.outbox().queuedMessages()).isZero();
        assertThat(connections.get(1).getTerminalSince()).isNull();
    }

    private int start(BinaryWebSocketHandler handler) throws Exception {
        tomcat = new Tomcat();
        tomcat.setBaseDir(baseDirectory.toString());
        tomcat.setPort(0);
        tomcat.getConnector();
        Context context = tomcat.addContext("", baseDirectory.toString());
        context.addServletContainerInitializer(new WsSci(), null);
        context.addServletContainerInitializer((classes, servletContext) -> {
            ServerContainer container = (ServerContainer) servletContext.getAttribute(ServerContainer.class.getName());
            try {
                container.addEndpoint(endpoint(handler));
            } catch (DeploymentException e) {
                throw new ServletException(e);
            }
        }, null);
        Tomcat.addServlet(context, "none", new HttpServlet() {
            private static final long serialVersionUID = 1L;
        });
        context.addServletMapping("/*", "none");
        tomcat.start();
        return tomcat.getConnector().getLocalPort();
    }

    private static ServerEndpointConfig endpoint(BinaryWebSocketHandler handler) {
        return ServerEndpointConfig.Builder.create(Endpoint.class, PATH)
            .configurator(new ServerEndpointConfig.Configurator() {
                @Override
                public <T> T getEndpointInstance(Class<T> endpointClass) {
                    StandardWebSocketSession session =
                        new StandardWebSocketSession(new HttpHeaders(), new ConcurrentHashMap<>(), null, null);
                    return endpointClass.cast(new StandardWebSocketHandlerAdapter(handler, session));
                }
            })
            .build();
    }

    private static RealtimeConnectionState subscribe(RealtimeConnectionRegistry registry, WebSocketSession session) {
        RealtimeConnectionState state = registry.register(session);
        Server server = TestServers.server("server", "admin@example.com");
        server.setId(SERVER_ID);
        state.authenticate(RealtimePrincipal.minecraft(server));
        registry.onAuthenticated(session);
        state.subscribe(Topic.TOPIC_MINECRAFT_PUNISHMENTS);
        return state;
    }

    private static RealtimeEnvelope event(int index) {
        return RealtimeEnvelope.newBuilder()
            .setEventId("event-" + index)
            .setPunishmentNotification(PunishmentNotificationEvent.newBuilder()
                .setPunishmentId("PUNISH" + index)
                .setUsername("x".repeat(EVENT_PAYLOAD_CHARACTERS)))
            .build();
    }

    private static RealtimeDispatchExecutor inlineDispatch(RealtimeProperties properties) {
        return new RealtimeDispatchExecutor(properties, new SimpleMeterRegistry()) {
            @Override
            public void execute(String serverId, Runnable task) {
                task.run();
            }
        };
    }

    private static final class StoppedReader implements WebSocket.Listener {
        @Override
        public void onOpen(WebSocket webSocket) {
        }
    }

    private static final class Reader implements WebSocket.Listener {
        private final AtomicInteger messages = new AtomicInteger();

        @Override
        public CompletionStage<?> onBinary(WebSocket webSocket, ByteBuffer data, boolean last) {
            if (last) {
                messages.incrementAndGet();
            }
            webSocket.request(1);
            return null;
        }

        boolean awaitAtLeast(int count) throws InterruptedException {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS);
            while (messages.get() < count && System.nanoTime() < deadline) {
                TimeUnit.MILLISECONDS.sleep(5);
            }
            return messages.get() >= count;
        }
    }
}

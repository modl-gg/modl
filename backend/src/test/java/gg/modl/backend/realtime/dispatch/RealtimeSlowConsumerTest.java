package gg.modl.backend.realtime.dispatch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import gg.modl.backend.realtime.auth.RealtimePrincipal;
import gg.modl.backend.realtime.config.RealtimeProperties;
import gg.modl.backend.realtime.lifecycle.RealtimeConnectionCleanup;
import gg.modl.backend.realtime.metrics.RealtimeMetrics;
import gg.modl.backend.realtime.rate.PluginInboundBudget;
import gg.modl.backend.realtime.rate.RealtimeMessageRateLimiter;
import gg.modl.backend.realtime.state.RealtimeConnectionRegistry;
import gg.modl.backend.realtime.state.RealtimeConnectionState;
import gg.modl.backend.realtime.transport.RealtimeCodec;
import gg.modl.backend.realtime.transport.RealtimeOutboundExecutor;
import gg.modl.backend.realtime.transport.RealtimeSessionOperations;
import gg.modl.backend.server.data.Server;
import gg.modl.backend.support.TestServers;
import gg.modl.proto.modl.v1.PunishmentNotificationEvent;
import gg.modl.proto.modl.v1.RealtimeCloseCode;
import gg.modl.proto.modl.v1.RealtimeEnvelope;
import gg.modl.proto.modl.v1.Topic;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;

class RealtimeSlowConsumerTest {
    private static final String SERVER_ID = "instance";
    private static final int EVENTS = 200;
    private static final int OUTBOX_MESSAGES = 32;
    private static final int BURST = 16;
    private static final long WAIT_MILLIS = 5_000;

    private final CountDownLatch clientStartsReading = new CountDownLatch(1);
    private final RealtimeOutboundExecutor outbound = new RealtimeOutboundExecutor(new RealtimeProperties());

    @AfterEach
    void releaseTheStoppedClient() throws InterruptedException {
        clientStartsReading.countDown();
        outbound.shutdown();
    }

    @Test
    void aClientThatStopsReadingNeitherDelaysOtherConnectionsNorGrowsMemoryAndIsClosedAsASlowConsumer() throws Exception {
        RealtimeProperties properties = new RealtimeProperties();
        properties.setOutboxMaxMessages(OUTBOX_MESSAGES);
        RealtimeConnectionRegistry registry = new RealtimeConnectionRegistry(properties);
        RealtimeMetrics metrics = new RealtimeMetrics(new SimpleMeterRegistry());
        RealtimeConnectionCleanup cleanup = new RealtimeConnectionCleanup(registry, new RealtimeMessageRateLimiter(properties), new PluginInboundBudget(), metrics, event -> { });
        RealtimeSessionOperations operations = new RealtimeSessionOperations(registry, cleanup, metrics, outbound);
        InProcessRealtimeEventDispatcher dispatcher = new InProcessRealtimeEventDispatcher(registry, new RealtimeCodec(properties),
            metrics, operations, new RealtimeTopicPayloadValidator(), inlineDispatch(properties));
        WebSocketSession stopped = session("stopped-reading");
        doAnswer(call -> {
            clientStartsReading.await();
            return null;
        }).when(stopped).sendMessage(any(BinaryMessage.class));
        WebSocketSession reading = session("reading");
        AtomicInteger received = new AtomicInteger();
        doAnswer(call -> received.incrementAndGet()).when(reading).sendMessage(any(BinaryMessage.class));
        RealtimeConnectionState stoppedState = connect(registry, stopped);
        connect(registry, reading);

        long publishingNanos = 0;
        for (int index = 0; index < EVENTS; index++) {
            long started = System.nanoTime();
            dispatcher.publish(new RealtimeOutboundEvent(SERVER_ID, Topic.TOPIC_MINECRAFT_PUNISHMENTS, event(index)));
            publishingNanos += System.nanoTime() - started;
            if ((index + 1) % BURST == 0) {
                awaitAtLeast(received, index + 1);
            }
        }

        assertThat(Duration.ofNanos(publishingNanos)).isLessThan(Duration.ofSeconds(2));
        verify(stopped, timeout(WAIT_MILLIS)).close(new CloseStatus(
            RealtimeCloseCode.REALTIME_CLOSE_CODE_SLOW_CONSUMER.getNumber(), "Realtime client is not reading its messages"));
        awaitAtLeast(received, EVENTS);
        assertThat(received.get()).isEqualTo(EVENTS);
        assertThat(stoppedState.outbox().queuedMessages()).isZero();
        assertThat(stoppedState.isClosing()).isTrue();
    }

    @Test
    void theSlowConsumerCloseIsAReconnectableCodeDistinctFromDisabled() {
        assertThat(RealtimeSessionOperations.SLOW_CONSUMER.getCode())
            .isEqualTo(RealtimeCloseCode.REALTIME_CLOSE_CODE_SLOW_CONSUMER.getNumber())
            .isNotEqualTo(RealtimeCloseCode.REALTIME_CLOSE_CODE_DISABLED.getNumber());
    }

    private static void awaitAtLeast(AtomicInteger received, int count) throws InterruptedException {
        long deadline = System.currentTimeMillis() + WAIT_MILLIS;
        while (received.get() < count && System.currentTimeMillis() < deadline) {
            TimeUnit.MILLISECONDS.sleep(5);
        }
    }

    private RealtimeConnectionState connect(RealtimeConnectionRegistry registry, WebSocketSession session) {
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
            .setPunishmentNotification(PunishmentNotificationEvent.getDefaultInstance())
            .build();
    }

    private static WebSocketSession session(String id) {
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn(id);
        when(session.isOpen()).thenReturn(true);
        when(session.getAttributes()).thenReturn(new ConcurrentHashMap<>());
        return session;
    }

    private static RealtimeDispatchExecutor inlineDispatch(RealtimeProperties properties) {
        return new RealtimeDispatchExecutor(properties, new SimpleMeterRegistry()) {
            @Override
            public void execute(String serverId, Runnable task) {
                task.run();
            }
        };
    }
}

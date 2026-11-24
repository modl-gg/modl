package gg.modl.backend.realtime.transport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import gg.modl.backend.realtime.config.RealtimeProperties;
import gg.modl.backend.realtime.lifecycle.RealtimeConnectionCleanup;
import gg.modl.backend.realtime.metrics.RealtimeMetrics;
import gg.modl.backend.realtime.rate.PluginInboundBudget;
import gg.modl.backend.realtime.rate.RealtimeMessageRateLimiter;
import gg.modl.backend.realtime.state.RealtimeConnectionRegistry;
import gg.modl.backend.realtime.state.RealtimeConnectionState;
import gg.modl.proto.modl.v1.RealtimeCloseCode;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;

class RealtimeOutboundCloseTest {
    private static final int FIRST_APPLICATION_CLOSE_CODE = 4000;
    private static final int LAST_APPLICATION_CLOSE_CODE = 4999;

    @Test
    void everyCloseReasonHasItsOwnApplicationCloseCode() {
        Set<Integer> codes = new HashSet<>();
        for (RealtimeCloseCode code : RealtimeCloseCode.values()) {
            if (code == RealtimeCloseCode.REALTIME_CLOSE_CODE_UNSPECIFIED || code == RealtimeCloseCode.UNRECOGNIZED) {
                continue;
            }
            assertTrue(code.getNumber() >= FIRST_APPLICATION_CLOSE_CODE && code.getNumber() <= LAST_APPLICATION_CLOSE_CODE,
                code + " is outside the application close code range");
            assertTrue(codes.add(code.getNumber()), code + " reuses a close code");
            assertEquals(code.getNumber(), RealtimeCloseStatuses.of(code, "reason").getCode());
        }
    }

    @Test
    void theBoundedOutboundPoolRejectsWorkOnceEveryWorkerIsBusy() throws Exception {
        ExecutorService workers = RealtimeOutboundExecutor.boundedWorkers(1);
        RealtimeOutboundExecutor executor = new RealtimeOutboundExecutor(workers);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch started = new CountDownLatch(1);
        try {
            assertTrue(executor.tryExecute(() -> {
                started.countDown();
                awaitQuietly(release);
            }));
            started.await();

            assertFalse(executor.tryExecute(() -> { }));
        } finally {
            release.countDown();
            executor.shutdown();
        }
    }

    @Test
    void aConnectionThatFindsNoFreeOutboundWorkerIsClosedAsOverloaded() throws Exception {
        RealtimeProperties properties = new RealtimeProperties();
        RealtimeConnectionRegistry registry = new RealtimeConnectionRegistry(properties);
        RealtimeSessionOperations operations = operations(registry, properties, new RealtimeOutboundExecutor(task -> {
            throw new RejectedExecutionException("saturated");
        }));
        WebSocketSession session = session("overloaded");
        RealtimeConnectionState state = registry.register(session);

        assertFalse(operations.deliver(session, state, message()));

        verify(session).close(RealtimeSessionOperations.OVERLOADED);
        assertEquals(RealtimeCloseCode.REALTIME_CLOSE_CODE_OVERLOADED.getNumber(), RealtimeSessionOperations.OVERLOADED.getCode());
    }

    @Test
    void sendThenCloseOnAFullOutboxClosesOnceOffTheCallerThread() throws Exception {
        RealtimeProperties properties = new RealtimeProperties();
        properties.setOutboxMaxMessages(1);
        RealtimeConnectionRegistry registry = new RealtimeConnectionRegistry(properties);
        List<Runnable> scheduled = new ArrayList<>();
        RealtimeSessionOperations operations = operations(registry, properties, new RealtimeOutboundExecutor(scheduled::add));
        WebSocketSession session = session("full-outbox");
        RealtimeConnectionState state = registry.register(session);
        CloseStatus requested = RealtimeCloseStatuses.of(RealtimeCloseCode.REALTIME_CLOSE_CODE_PROTOCOL_VIOLATION, "bad frame");

        assertTrue(operations.deliver(session, state, message()));
        operations.sendThenClose(session, state, message(), requested, "error");

        verify(session, never()).close(any(CloseStatus.class));
        for (Runnable task : new ArrayList<>(scheduled)) {
            task.run();
        }
        verify(session, times(1)).close(any(CloseStatus.class));
        verify(session).close(RealtimeSessionOperations.SLOW_CONSUMER);
    }

    private static RealtimeSessionOperations operations(RealtimeConnectionRegistry registry, RealtimeProperties properties,
                                                        RealtimeOutboundExecutor executor) {
        RealtimeMetrics metrics = new RealtimeMetrics(new SimpleMeterRegistry());
        RealtimeConnectionCleanup cleanup = new RealtimeConnectionCleanup(registry, new RealtimeMessageRateLimiter(properties), new PluginInboundBudget(), metrics, event -> { });
        return new RealtimeSessionOperations(registry, cleanup, metrics, executor);
    }

    private static BinaryMessage message() {
        return new BinaryMessage(Arrays.copyOf(new byte[] {1}, 16));
    }

    private static WebSocketSession session(String id) {
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn(id);
        when(session.isOpen()).thenReturn(true);
        when(session.getAttributes()).thenReturn(new ConcurrentHashMap<>());
        return session;
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}

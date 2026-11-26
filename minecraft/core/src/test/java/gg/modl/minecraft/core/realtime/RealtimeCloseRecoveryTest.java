package gg.modl.minecraft.core.realtime;

import gg.modl.minecraft.core.boot.VersionMismatchFailure;
import gg.modl.minecraft.core.boot.VersionMismatchReports;
import gg.modl.minecraft.core.connection.BackendEndpoint;
import gg.modl.minecraft.core.plugin.PluginInfo;
import gg.modl.minecraft.core.support.RecordingPluginLogger;
import gg.modl.minecraft.core.support.TestConnections;
import gg.modl.update.CompatibilityHeaders;
import gg.modl.proto.modl.v1.ErrorCode;
import gg.modl.proto.modl.v1.RealtimeCloseCode;
import gg.modl.proto.modl.v1.RealtimeEnvelope;
import gg.modl.proto.modl.v1.ServerHello;
import gg.modl.proto.modl.v1.Topic;
import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RealtimeCloseRecoveryTest {
    private static final long RECONNECT_WAIT_SECONDS = 45;
    private static final long NO_RECONNECT_WAIT_MILLIS = 3_500;

    private final VersionMismatchReports mismatches = new VersionMismatchReports();
    private ClosingBackend backend;
    private RealtimeConnection connection;

    @AfterEach
    void stop() throws InterruptedException {
        if (connection != null) connection.stop();
        if (backend != null) backend.stop(1000);
    }

    @Test
    void aSlowConsumerCloseReconnectsAndResyncsInsteadOfEndingRealtime() throws Exception {
        RecordingListener listener = new RecordingListener(2);
        start(RealtimeCloseCode.REALTIME_CLOSE_CODE_SLOW_CONSUMER, null, Closes.FIRST_CONNECTION, listener);

        assertTrue(listener.connections.await(RECONNECT_WAIT_SECONDS, TimeUnit.SECONDS), "the plugin never reconnected");

        assertTrue(backend.hellos.get() >= 2);
        assertEquals(true, listener.connects.get(0));
        assertEquals(false, listener.connects.get(1));
        assertTrue(listener.terminations.isEmpty());
    }

    @Test
    void aDisabledCloseEndsRealtimeWithoutReconnecting() throws Exception {
        RecordingListener listener = new RecordingListener(2);
        start(RealtimeCloseCode.REALTIME_CLOSE_CODE_DISABLED, listener);

        assertTrue(listener.terminated.await(RECONNECT_WAIT_SECONDS, TimeUnit.SECONDS), "realtime was not ended");
        assertNoFurtherConnection();
        assertEquals(Collections.singletonList(TerminationReason.DISABLED_BY_BACKEND), listener.terminations);
    }

    @Test
    void aForbiddenErrorBeforeAProtocolViolationCloseIsNotARejectedKey() throws Exception {
        RecordingListener listener = new RecordingListener(2);
        start(RealtimeCloseCode.REALTIME_CLOSE_CODE_PROTOCOL_VIOLATION, errorFrame(ErrorCode.ERROR_CODE_FORBIDDEN), listener);

        assertTrue(listener.terminated.await(RECONNECT_WAIT_SECONDS, TimeUnit.SECONDS), "realtime was not ended");

        assertEquals(Collections.singletonList(TerminationReason.PROTOCOL_VIOLATION), listener.terminations);
    }

    @Test
    void anErrorFrameAloneNeverEndsRealtime() throws Exception {
        RecordingListener listener = new RecordingListener(2);
        start(RealtimeCloseCode.REALTIME_CLOSE_CODE_OVERLOADED, errorFrame(ErrorCode.ERROR_CODE_UNAUTHORIZED), Closes.FIRST_CONNECTION, listener);

        assertTrue(listener.connections.await(RECONNECT_WAIT_SECONDS, TimeUnit.SECONDS), "the plugin never reconnected");

        assertTrue(backend.hellos.get() >= 2);
        assertTrue(listener.terminations.isEmpty());
    }

    @Test
    void anAuthenticationFailedCloseIsTheRejectedKey() throws Exception {
        RecordingListener listener = new RecordingListener(2);
        start(RealtimeCloseCode.REALTIME_CLOSE_CODE_AUTHENTICATION_FAILED, errorFrame(ErrorCode.ERROR_CODE_UNAUTHORIZED), listener);

        assertTrue(listener.terminated.await(RECONNECT_WAIT_SECONDS, TimeUnit.SECONDS), "realtime was not ended");
        assertNoFurtherConnection();
        assertEquals(Collections.singletonList(TerminationReason.CREDENTIALS_REJECTED), listener.terminations);
    }

    @Test
    void aReplacedSessionIsReportedAndWaitsBeforeReconnecting() throws Exception {
        RecordingListener listener = new RecordingListener(2);
        start(RealtimeCloseCode.REALTIME_CLOSE_CODE_SESSION_REPLACED, listener);

        assertTrue(listener.replaced.await(RECONNECT_WAIT_SECONDS, TimeUnit.SECONDS), "the replacement was not reported");
        assertNoFurtherConnection();
        assertTrue(listener.terminations.isEmpty());
        assertFalse(connection.isConnected());
    }

    @Test
    void aVersionMismatchCloseReachesTheLifecycleAndTheHandshakeNamesThePlugin() throws Exception {
        List<VersionMismatchFailure> reported = new CopyOnWriteArrayList<>();
        CountDownLatch mismatch = new CountDownLatch(1);
        mismatches.listen(failure -> {
            reported.add(failure);
            mismatch.countDown();
        });
        start(RealtimeCloseCode.REALTIME_CLOSE_CODE_VERSION_MISMATCH, new RecordingListener(2));

        assertTrue(mismatch.await(RECONNECT_WAIT_SECONDS, TimeUnit.SECONDS), "the mismatch was not reported");

        assertEquals(1, reported.size());
        assertEquals(PluginInfo.VERSION, backend.handshakePluginVersion.get());
        assertEquals("key", backend.handshakeApiKey.get());
    }

    private static RealtimeEnvelope errorFrame(ErrorCode code) {
        return RealtimeEnvelope.newBuilder().setProtocolVersion(1)
                .setError(gg.modl.proto.modl.v1.Error.newBuilder().setCode(code).setMessage(code.name()))
                .build();
    }

    private void assertNoFurtherConnection() throws InterruptedException {
        int hellosAtTheEnd = backend.hellos.get();
        TimeUnit.MILLISECONDS.sleep(NO_RECONNECT_WAIT_MILLIS);
        assertEquals(hellosAtTheEnd, backend.hellos.get());
    }

    private void start(RealtimeCloseCode closeCode, RecordingListener listener) throws InterruptedException {
        start(closeCode, null, listener);
    }

    private void start(RealtimeCloseCode closeCode, RealtimeEnvelope errorBeforeClose, RecordingListener listener)
            throws InterruptedException {
        start(closeCode, errorBeforeClose, Closes.EVERY_CONNECTION, listener);
    }

    private void start(RealtimeCloseCode closeCode, RealtimeEnvelope errorBeforeClose, Closes closes, RecordingListener listener)
            throws InterruptedException {
        backend = new ClosingBackend(closeCode, errorBeforeClose, closes);
        backend.setReuseAddr(true);
        backend.start();
        assertTrue(backend.started.await(RECONNECT_WAIT_SECONDS, TimeUnit.SECONDS));
        BackendEndpoint endpoint = BackendEndpoint.of("http://127.0.0.1:" + backend.getPort(), "key");
        connection = new RealtimeConnection(TestConnections.reportingTo(endpoint, mismatches), 1,
                Collections.singleton(Topic.TOPIC_MINECRAFT_PUNISHMENTS), () -> "Server 1", null,
                new RecordingPluginLogger(), false);
        connection.addListener(listener);
        connection.start();
    }

    private enum Closes {
        FIRST_CONNECTION,
        EVERY_CONNECTION
    }

    private static final class ClosingBackend extends WebSocketServer {
        private final RealtimeCloseCode closeCode;
        private final RealtimeEnvelope errorBeforeClose;
        private final Closes closes;
        private final CountDownLatch started = new CountDownLatch(1);
        private final AtomicInteger hellos = new AtomicInteger();
        private final AtomicReference<String> handshakePluginVersion = new AtomicReference<>();
        private final AtomicReference<String> handshakeApiKey = new AtomicReference<>();

        private ClosingBackend(RealtimeCloseCode closeCode, RealtimeEnvelope errorBeforeClose, Closes closes) {
            super(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
            this.closeCode = closeCode;
            this.errorBeforeClose = errorBeforeClose;
            this.closes = closes;
        }

        @Override
        public void onOpen(WebSocket socket, ClientHandshake handshake) {
            handshakePluginVersion.set(handshake.getFieldValue(CompatibilityHeaders.PLUGIN_VERSION));
            handshakeApiKey.set(handshake.getFieldValue("X-API-Key"));
        }

        @Override
        public void onClose(WebSocket socket, int code, String reason, boolean remote) {
        }

        @Override
        public void onMessage(WebSocket socket, String message) {
        }

        @Override
        public void onMessage(WebSocket socket, ByteBuffer message) {
            RealtimeEnvelope envelope;
            try {
                envelope = RealtimeEnvelope.parseFrom(message);
            } catch (Exception malformed) {
                return;
            }
            if (envelope.getPayloadCase() != RealtimeEnvelope.PayloadCase.CLIENT_HELLO) return;
            int hello = hellos.incrementAndGet();
            socket.send(RealtimeEnvelope.newBuilder().setProtocolVersion(1)
                    .setServerHello(ServerHello.newBuilder().setProtocolVersion(1).setConnectionId("c" + hello))
                    .build().toByteArray());
            if (closes == Closes.FIRST_CONNECTION && hello != 1) return;
            if (errorBeforeClose != null) socket.send(errorBeforeClose.toByteArray());
            socket.close(closeCode.getNumber(), closeCode.name());
        }

        @Override
        public void onError(WebSocket socket, Exception exception) {
        }

        @Override
        public void onStart() {
            started.countDown();
        }
    }

    private static final class RecordingListener implements ConnectionListener {
        private final CountDownLatch connections;
        private final CountDownLatch terminated = new CountDownLatch(1);
        private final CountDownLatch replaced = new CountDownLatch(1);
        private final List<Boolean> connects = new CopyOnWriteArrayList<>();
        private final List<TerminationReason> terminations = new CopyOnWriteArrayList<>();

        private RecordingListener(int expectedConnections) {
            this.connections = new CountDownLatch(expectedConnections);
        }

        @Override
        public void onConnected(boolean first) {
            connects.add(first);
            connections.countDown();
        }

        @Override
        public void onDisconnected() {
        }

        @Override
        public void onResyncAdvised() {
        }

        @Override
        public void onReplaced() {
            replaced.countDown();
        }

        @Override
        public void onTerminated(TerminationReason reason) {
            terminations.add(reason);
            terminated.countDown();
        }
    }
}

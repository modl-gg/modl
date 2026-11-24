package gg.modl.minecraft.core.realtime;

import gg.modl.minecraft.core.boot.VersionMismatchFailure;
import gg.modl.minecraft.core.connection.BackendConnections;
import gg.modl.minecraft.core.connection.BackendEndpoint;
import gg.modl.minecraft.core.plugin.PluginInfo;
import gg.modl.minecraft.core.util.PluginLogger;
import gg.modl.minecraft.core.util.Scheduling;
import gg.modl.proto.modl.v1.Ack;
import gg.modl.proto.modl.v1.AckKind;
import gg.modl.proto.modl.v1.ClientHello;
import gg.modl.proto.modl.v1.ClientKind;
import gg.modl.proto.modl.v1.ErrorCode;
import gg.modl.proto.modl.v1.Heartbeat;
import gg.modl.proto.modl.v1.RealtimeCloseCode;
import gg.modl.proto.modl.v1.RealtimeEnvelope;
import gg.modl.proto.modl.v1.RealtimeEnvelope.PayloadCase;
import gg.modl.proto.modl.v1.ReconnectAction;
import gg.modl.proto.modl.v1.ReconnectAdvice;
import gg.modl.proto.modl.v1.ServerRole;
import gg.modl.proto.modl.v1.Subscribe;
import gg.modl.proto.modl.v1.Topic;
import gg.modl.update.ReleaseVersion;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import org.jspecify.annotations.Nullable;

import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Supplier;

public final class RealtimeConnection {
    private static final String HOST_HEADER = "Host";
    private static final long HEARTBEAT_INTERVAL_SECONDS = 25;
    private static final long INBOUND_LIVENESS_TIMEOUT_MILLIS = HEARTBEAT_INTERVAL_SECONDS * 3 * 1000;
    private static final long INITIAL_RECONNECT_DELAY_MS = 1000;
    private static final long MAX_RECONNECT_DELAY_MS = 30000;
    private static final long MAX_ADVISED_RECONNECT_DELAY_MS = 60000;
    static final long RATE_LIMITED_RECONNECT_DELAY_MS = 30000;
    static final long VERSION_MISMATCH_RECONNECT_DELAY_MS = 60000;
    public static final long SESSION_REPLACED_RECONNECT_DELAY_MS = 60000;
    private static final Set<PayloadCase> TRANSPORT_PAYLOADS = Collections.unmodifiableSet(EnumSet.of(
        PayloadCase.SERVER_HELLO, PayloadCase.HEARTBEAT, PayloadCase.RECONNECT_ADVICE, PayloadCase.ERROR));

    private enum State { DISCONNECTED, CONNECTING, CONNECTED, TERMINAL }

    private final BackendConnections connections;
    private final int protocolVersion;
    private final Set<Topic> topics;
    private final Supplier<String> serverName;
    private final @Nullable String serverInstanceId;
    private final ServerRole role;
    private final PluginLogger logger;
    private final boolean debugMode;
    private final ScheduledExecutorService executor;
    private final AtomicLong heartbeatSequence = new AtomicLong(0);
    private final Random random = new Random();
    private final ConcurrentMap<PayloadCase, RealtimeInbound> inbound = new ConcurrentHashMap<>();
    private final List<ConnectionListener> listeners = new CopyOnWriteArrayList<>();

    private final Object stateLock = new Object();
    private volatile State state = State.DISCONNECTED;
    private boolean started;
    private @Nullable WebSocketClient client;
    private @Nullable ScheduledFuture<?> heartbeatTask;
    private boolean connectedOnce;
    private int reconnectAttempt;
    private boolean reconnectScheduled;
    private @Nullable Long reconnectDelayOverrideMs;
    private volatile long lastInboundFrameMillis;
    private volatile String advertisedServerVersion = "";

    public RealtimeConnection(BackendConnections connections, int protocolVersion, Set<Topic> topics,
                              Supplier<String> serverName, @Nullable String serverInstanceId,
                              PluginLogger logger, boolean debugMode) {
        this(connections, protocolVersion, topics, serverName, serverInstanceId, ServerRole.SERVER_ROLE_CORE, logger, debugMode);
    }

    public RealtimeConnection(BackendConnections connections, int protocolVersion, Set<Topic> topics,
                              Supplier<String> serverName, @Nullable String serverInstanceId, ServerRole role,
                              PluginLogger logger, boolean debugMode) {
        this.connections = connections;
        this.role = role;
        this.protocolVersion = protocolVersion;
        this.topics = Collections.unmodifiableSet(new LinkedHashSet<>(topics));
        this.serverName = serverName;
        this.serverInstanceId = serverInstanceId;
        this.logger = logger;
        this.debugMode = debugMode;
        this.executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "modl-realtime");
            thread.setDaemon(true);
            return thread;
        });
    }

    public void register(PayloadCase payloadCase, RealtimeInbound handler) {
        if (TRANSPORT_PAYLOADS.contains(payloadCase)) {
            throw new IllegalArgumentException(payloadCase + " is handled by the realtime transport");
        }
        if (inbound.putIfAbsent(payloadCase, handler) != null) {
            throw new IllegalStateException("a realtime handler is already registered for " + payloadCase);
        }
    }

    public void addListener(ConnectionListener listener) {
        listeners.add(listener);
    }

    public void start() {
        synchronized (stateLock) {
            if (started || state != State.DISCONNECTED) return;
            started = true;
            state = State.CONNECTING;
        }
        executor.execute(this::connectOnce);
    }

    public void stop() {
        shutdown();
    }

    public boolean isConnected() {
        return state == State.CONNECTED;
    }

    public boolean send(RealtimeEnvelope envelope) {
        WebSocketClient target;
        synchronized (stateLock) {
            target = client;
            if (state != State.CONNECTED || target == null) return false;
        }
        return send(target, envelope);
    }

    private boolean isTerminated() {
        return state == State.TERMINAL;
    }

    private boolean isCurrent(WebSocketClient source) {
        synchronized (stateLock) {
            return source == client;
        }
    }

    private void connectOnce() {
        WebSocketClient nextClient;
        synchronized (stateLock) {
            if (state == State.TERMINAL) return;
            state = State.CONNECTING;
            reconnectScheduled = false;
        }

        try {
            BackendEndpoint endpoint = connections.endpoint();
            Map<String, String> headers = connections.requestHeaders();
            headers.put(HOST_HEADER, endpoint.getAddress().authority());

            nextClient = new WebSocketClient(endpoint.resolvedRealtimeUri(), headers) {
                @Override
                public void onOpen(ServerHandshake handshake) {
                    handleOpen(this);
                }

                @Override
                public void onMessage(String message) {
                    if (debugMode) logger.warning("[Realtime] Ignoring text frame from backend");
                }

                @Override
                public void onMessage(ByteBuffer bytes) {
                    handleBinaryMessage(this, bytes);
                }

                @Override
                public void onClose(int code, String reason, boolean remote) {
                    handleClose(this, code, reason);
                }

                @Override
                public void onError(Exception exception) {
                    handleError(this, exception);
                }
            };

            synchronized (stateLock) {
                if (state == State.TERMINAL) {
                    safeClose(nextClient);
                    return;
                }
                this.client = nextClient;
            }
            nextClient.connect();
        } catch (Exception e) {
            logger.warning("[Realtime] Failed to start WebSocket: " + e.getMessage());
            teardown();
            scheduleReconnect();
        }
    }

    private void handleOpen(WebSocketClient openedClient) {
        if (!isCurrent(openedClient)) return;
        if (debugMode) logger.info("[Realtime] WebSocket connected, sending ClientHello");
        send(openedClient, buildClientHello());
        synchronized (stateLock) {
            if (state == State.TERMINAL || openedClient != client) return;
            lastInboundFrameMillis = System.currentTimeMillis();
            cancelHeartbeatLocked();
            heartbeatTask = executor.scheduleAtFixedRate(() -> heartbeatTick(openedClient),
                HEARTBEAT_INTERVAL_SECONDS, HEARTBEAT_INTERVAL_SECONDS, TimeUnit.SECONDS);
        }
    }

    private void handleBinaryMessage(WebSocketClient source, ByteBuffer bytes) {
        if (!isCurrent(source)) return;
        lastInboundFrameMillis = System.currentTimeMillis();
        try {
            RealtimeEnvelope envelope = RealtimeEnvelope.parseFrom(bytes);
            if (!envelope.getEventId().isEmpty()) {
                send(source, buildTransportAck(envelope.getEventId()));
            }

            switch (envelope.getPayloadCase()) {
                case SERVER_HELLO:
                    onServerHello(source, envelope);
                    break;
                case HEARTBEAT:
                    if (debugMode) logger.info("[Realtime] Backend heartbeat received");
                    break;
                case RECONNECT_ADVICE:
                    handleReconnectAdvice(envelope.getReconnectAdvice());
                    break;
                case ERROR:
                    onBackendError(envelope.getError());
                    break;
                default:
                    dispatchInbound(envelope);
                    break;
            }
        } catch (Exception e) {
            logger.warning("[Realtime] Failed to process WebSocket frame: " + e.getMessage());
        }
    }

    private void dispatchInbound(RealtimeEnvelope envelope) {
        RealtimeInbound handler = inbound.get(envelope.getPayloadCase());
        if (handler != null) handler.receive(envelope);
        else if (debugMode) logger.info("[Realtime] Ignored advisory payload: " + envelope.getPayloadCase());
    }

    private void onServerHello(WebSocketClient source, RealtimeEnvelope envelope) {
        boolean firstConnect;
        synchronized (stateLock) {
            if (state == State.TERMINAL || source != client) return;
            state = State.CONNECTED;
            firstConnect = !connectedOnce;
            connectedOnce = true;
        }
        send(source, buildSubscribe());
        notifyListeners(listener -> listener.onConnected(firstConnect));
        if (debugMode) logger.info("[Realtime] ServerHello accepted " + envelope.getServerHello().getAcceptedTopicsCount() + " topics");
    }

    private void handleReconnectAdvice(ReconnectAdvice advice) {
        if (advice.getAction() == ReconnectAction.RECONNECT_ACTION_STOP) {
            logger.warning("[Realtime] Backend requested realtime stop: " + advice.getMessage());
            stopFor(TerminationReason.STOPPED_BY_BACKEND);
            return;
        }
        if (advice.getAction() == ReconnectAction.RECONNECT_ACTION_RECONNECT && advice.hasRetryAfterMs()) {
            synchronized (stateLock) {
                reconnectDelayOverrideMs = (long) advice.getRetryAfterMs();
            }
        }
        if (advice.getAction() == ReconnectAction.RECONNECT_ACTION_RESYNC) {
            notifyListeners(ConnectionListener::onResyncAdvised);
        }
    }

    private void handleClose(WebSocketClient source, int code, String reason) {
        Optional<TerminationReason> termination = terminationForClose(code);
        boolean reconnect = !termination.isPresent();
        boolean wasCurrent;
        synchronized (stateLock) {
            if (source != client || state == State.TERMINAL) {
                wasCurrent = false;
            } else {
                wasCurrent = true;
                cancelHeartbeatLocked();
                closeClientLocked();
                state = reconnect ? State.DISCONNECTED : State.TERMINAL;
                if (isRateLimitedClose(code)) reconnectDelayOverrideMs = RATE_LIMITED_RECONNECT_DELAY_MS;
                if (isVersionMismatchClose(code)) reconnectDelayOverrideMs = VERSION_MISMATCH_RECONNECT_DELAY_MS;
                if (isSessionReplacedClose(code)) reconnectDelayOverrideMs = SESSION_REPLACED_RECONNECT_DELAY_MS;
            }
        }
        if (!wasCurrent) return;
        if (isVersionMismatchClose(code)) connections.report(versionMismatch(advertisedServerVersion));
        advertisedServerVersion = "";
        notifyListeners(ConnectionListener::onDisconnected);
        if (isSessionReplacedClose(code)) notifyListeners(ConnectionListener::onReplaced);
        if (debugMode) logger.info("[Realtime] WebSocket closed (" + code + "): " + reason);
        if (reconnect) {
            scheduleReconnect();
            return;
        }
        logger.warning("[Realtime] Backend ended realtime (" + code + "): " + reason
            + "; falling back to periodic sync until the next restart");
        notifyListeners(listener -> listener.onTerminated(termination.get()));
    }

    private void handleError(WebSocketClient source, Exception exception) {
        boolean wasCurrent;
        synchronized (stateLock) {
            if (source != client || state == State.TERMINAL) {
                wasCurrent = false;
            } else {
                wasCurrent = true;
                cancelHeartbeatLocked();
                closeClientLocked();
                state = State.DISCONNECTED;
            }
        }
        if (!wasCurrent) return;
        logger.warning("[Realtime] WebSocket error: " + exception.getMessage());
        notifyListeners(ConnectionListener::onDisconnected);
        scheduleReconnect();
    }

    private void heartbeatTick(WebSocketClient target) {
        if (!isCurrent(target)) return;
        if (System.currentTimeMillis() - lastInboundFrameMillis > INBOUND_LIVENESS_TIMEOUT_MILLIS) {
            logger.warning("[Realtime] No frames received within "
                + (INBOUND_LIVENESS_TIMEOUT_MILLIS / 1000) + "s; forcing reconnect");
            teardown();
            scheduleReconnect();
            return;
        }
        resetBackoffWhileConnected();
        sendHeartbeat(target);
    }

    private void resetBackoffWhileConnected() {
        synchronized (stateLock) {
            if (state == State.CONNECTED) reconnectAttempt = 0;
        }
    }

    private void teardown() {
        synchronized (stateLock) {
            if (state == State.TERMINAL) return;
            cancelHeartbeatLocked();
            closeClientLocked();
            state = State.DISCONNECTED;
        }
        notifyListeners(ConnectionListener::onDisconnected);
    }

    private void stopFor(TerminationReason reason) {
        if (shutdown()) notifyListeners(listener -> listener.onTerminated(reason));
    }

    private boolean shutdown() {
        boolean wasActive = terminate();
        executor.shutdownNow();
        return wasActive;
    }

    private boolean terminate() {
        boolean wasActive;
        synchronized (stateLock) {
            wasActive = state != State.TERMINAL;
            state = State.TERMINAL;
            cancelHeartbeatLocked();
            closeClientLocked();
        }
        notifyListeners(ConnectionListener::onDisconnected);
        return wasActive;
    }

    private void notifyListeners(Consumer<ConnectionListener> notification) {
        for (ConnectionListener listener : listeners) {
            notification.accept(listener);
        }
    }

    private void cancelHeartbeatLocked() {
        ScheduledFuture<?> task = heartbeatTask;
        if (task != null) {
            task.cancel(false);
            heartbeatTask = null;
        }
    }

    private void closeClientLocked() {
        WebSocketClient open = client;
        if (open != null) {
            safeClose(open);
            client = null;
        }
    }

    private static void safeClose(WebSocketClient target) {
        try {
            target.close();
        } catch (Exception ignored) {
        }
    }

    private void scheduleReconnect() {
        long delayMs;
        synchronized (stateLock) {
            if (state == State.TERMINAL || reconnectScheduled) return;
            reconnectScheduled = true;
            delayMs = nextReconnectDelayLocked();
        }
        try {
            Scheduling.after(executor, this::connectOnce, delayMs, TimeUnit.MILLISECONDS, logger);
        } catch (RejectedExecutionException e) {
            synchronized (stateLock) {
                reconnectScheduled = false;
            }
            if (debugMode) logger.warning("[Realtime] Reconnect rejected: " + e.getMessage());
        }
    }

    private long nextReconnectDelayLocked() {
        Long override = reconnectDelayOverrideMs;
        if (override != null) {
            reconnectDelayOverrideMs = null;
            return advisedReconnectDelayMs(override);
        }
        int attempt = Math.min(reconnectAttempt++, 5);
        long baseDelay = Math.min(MAX_RECONNECT_DELAY_MS, INITIAL_RECONNECT_DELAY_MS << attempt);
        long jitter = random.nextInt((int) Math.min(baseDelay, 1000L) + 1);
        return baseDelay + jitter;
    }

    private long advisedReconnectDelayMs(long retryAfterMs) {
        long cappedDelay = capAdvisedReconnectDelayMs(retryAfterMs);
        long jitterBound = Math.max(1L, Math.min(cappedDelay, 1000L));
        return cappedDelay + random.nextInt((int) jitterBound + 1);
    }

    static long capAdvisedReconnectDelayMs(long retryAfterMs) {
        return Math.max(0L, Math.min(retryAfterMs, MAX_ADVISED_RECONNECT_DELAY_MS));
    }

    static Optional<TerminationReason> terminationForClose(int code) {
        RealtimeCloseCode closeCode = RealtimeCloseCode.forNumber(code);
        if (closeCode == null) return Optional.empty();
        switch (closeCode) {
            case REALTIME_CLOSE_CODE_DISABLED:
                return Optional.of(TerminationReason.DISABLED_BY_BACKEND);
            case REALTIME_CLOSE_CODE_AUTHENTICATION_FAILED:
                return Optional.of(TerminationReason.CREDENTIALS_REJECTED);
            case REALTIME_CLOSE_CODE_UNSUPPORTED_PROTOCOL:
                return Optional.of(TerminationReason.PROTOCOL_UNSUPPORTED);
            case REALTIME_CLOSE_CODE_PROTOCOL_VIOLATION:
                return Optional.of(TerminationReason.PROTOCOL_VIOLATION);
            default:
                return Optional.empty();
        }
    }

    static boolean isRateLimitedClose(int code) {
        return code == RealtimeCloseCode.REALTIME_CLOSE_CODE_RATE_LIMITED.getNumber();
    }

    private void onBackendError(gg.modl.proto.modl.v1.Error error) {
        if (error.hasServerVersion()) advertisedServerVersion = error.getServerVersion();
        if (error.getCode() == ErrorCode.ERROR_CODE_RATE_LIMITED || error.getCode() == ErrorCode.ERROR_CODE_VERSION_MISMATCH) {
            logger.debug("[Realtime] Backend error: " + error.getMessage());
            return;
        }
        logger.warning("[Realtime] Backend error: " + error.getMessage());
    }

    static boolean isSessionReplacedClose(int code) {
        return code == RealtimeCloseCode.REALTIME_CLOSE_CODE_SESSION_REPLACED.getNumber();
    }

    static boolean isVersionMismatchClose(int code) {
        return code == RealtimeCloseCode.REALTIME_CLOSE_CODE_VERSION_MISMATCH.getNumber();
    }

    VersionMismatchFailure versionMismatch(String advertisedVersion) {
        String serverVersion = ReleaseVersion.tryParse(advertisedVersion) == null ? "" : advertisedVersion;
        return connections.mismatchWith(serverVersion);
    }

    private RealtimeEnvelope buildClientHello() {
        ClientHello.Builder hello = ClientHello.newBuilder()
            .setClientKind(ClientKind.CLIENT_KIND_MINECRAFT_PLUGIN)
            .setProtocolVersion(protocolVersion)
            .setServerRole(role)
            .setPluginVersion(PluginInfo.VERSION)
            .addAllSupportedTopics(topics);
        String name = serverName.get();
        if (name != null && !name.trim().isEmpty()) {
            hello.setServerName(name);
        }
        if (serverInstanceId != null) {
            hello.setServerInstanceId(serverInstanceId);
        }
        return baseEnvelope().setClientHello(hello).build();
    }

    private RealtimeEnvelope buildSubscribe() {
        return baseEnvelope()
            .setSubscribe(Subscribe.newBuilder().addAllTopics(topics))
            .build();
    }

    private RealtimeEnvelope buildTransportAck(String eventId) {
        return baseEnvelope()
            .setAck(Ack.newBuilder()
                .setKind(AckKind.ACK_KIND_TRANSPORT)
                .setEventId(eventId))
            .build();
    }

    private RealtimeEnvelope heartbeat(long sequence) {
        return baseEnvelope()
            .setHeartbeat(Heartbeat.newBuilder().setSequence(sequence))
            .build();
    }

    private void sendHeartbeat(WebSocketClient target) {
        send(target, heartbeat(heartbeatSequence.incrementAndGet()));
    }

    private RealtimeEnvelope.Builder baseEnvelope() {
        return RealtimeEnvelope.newBuilder().setProtocolVersion(protocolVersion);
    }

    private boolean send(WebSocketClient target, RealtimeEnvelope envelope) {
        if (isTerminated() || target == null || !target.isOpen()) return false;
        try {
            target.send(envelope.toByteArray());
            return true;
        } catch (Exception e) {
            logger.warning("[Realtime] Failed to send WebSocket frame: " + e.getMessage());
            return false;
        }
    }
}

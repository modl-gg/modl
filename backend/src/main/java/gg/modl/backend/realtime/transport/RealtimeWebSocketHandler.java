package gg.modl.backend.realtime.transport;

import com.google.protobuf.InvalidProtocolBufferException;
import gg.modl.backend.infrastructure.web.HttpOrigin;
import gg.modl.backend.network.NetworkHub;
import gg.modl.backend.realtime.auth.RealtimeAuthenticationException;
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
import gg.modl.proto.modl.v1.ClientHello;
import gg.modl.proto.modl.v1.ErrorCode;
import gg.modl.proto.modl.v1.RealtimeCloseCode;
import gg.modl.proto.modl.v1.RealtimeEnvelope;
import gg.modl.proto.modl.v1.Topic;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.BinaryWebSocketHandler;

@Component
@RequiredArgsConstructor
public class RealtimeWebSocketHandler extends BinaryWebSocketHandler {
    private static final String DISABLED_MESSAGE = "Realtime WebSocket is disabled";
    private static final String RATE_LIMITED_MESSAGE = "Realtime client message rate limit exceeded";
    static final CloseStatus TRANSPORT_FAILURE =
        RealtimeCloseStatuses.of(RealtimeCloseCode.REALTIME_CLOSE_CODE_TRANSPORT_FAILURE, "Realtime transport failed");

    private final RealtimeProperties properties;
    private final RealtimeCodec codec;
    private final RealtimeAuthenticator authenticator;
    private final RealtimeTopicAuthorizer topicAuthorizer;
    private final RealtimeConnectionRegistry connectionRegistry;
    private final RealtimeMessageRateLimiter rateLimiter;
    private final RealtimeConnectionCleanup connectionCleanup;
    private final RealtimeMetrics metrics;
    private final RealtimeSessionOperations sessionOperations;
    private final RealtimeUnauthenticatedConnectionLimiter connectionLimiter;
    private final PluginInboundBudget pluginBudget;
    private final NetworkHub networkHub;

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        RealtimeConnectionState state = connectionRegistry.register(session);
        session.getAttributes().put(RealtimeSessionAttributes.CONNECTION_ID, state.getConnectionId());
        metrics.recordConnect();

        if (!properties.isEnabled()) {
            metrics.recordReject(state, "disabled");
            sessionOperations.requestClose(session, state,
                RealtimeCloseStatuses.of(RealtimeCloseCode.REALTIME_CLOSE_CODE_DISABLED, DISABLED_MESSAGE), "disabled");
        }
    }

    @Override
    protected void handleBinaryMessage(WebSocketSession session, BinaryMessage message) throws Exception {
        RealtimeConnectionState state = stateForIncomingFrame(session);
        if (state == null) {
            return;
        }
        if (!admitInbound(session, state)) {
            return;
        }

        if (!properties.isEnabled()) {
            metrics.recordReject(state, "disabled");
            closeWithError(session, state, ErrorCode.ERROR_CODE_FORBIDDEN, DISABLED_MESSAGE, RealtimeCloseCode.REALTIME_CLOSE_CODE_DISABLED);
            return;
        }

        RealtimeEnvelope envelope;
        try {
            envelope = codec.parse(message);
        } catch (OversizedRealtimeFrameException exception) {
            metrics.recordReject(state, "oversized_frame");
            closeWithError(session, state, ErrorCode.ERROR_CODE_INVALID_MESSAGE, "Realtime frame is too large",
                RealtimeCloseCode.REALTIME_CLOSE_CODE_FRAME_TOO_LARGE);
            return;
        } catch (InvalidProtocolBufferException exception) {
            metrics.recordReject(state, "malformed_frame");
            closeWithError(session, state, ErrorCode.ERROR_CODE_INVALID_MESSAGE, "Malformed realtime protobuf frame",
                RealtimeCloseCode.REALTIME_CLOSE_CODE_MALFORMED_FRAME);
            return;
        }

        if (!state.isAuthenticated()) {
            handleHandshake(session, state, envelope);
            return;
        }

        handleAuthenticatedMessage(session, state, envelope);
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        RealtimeConnectionState state = existingState(session);
        if (state == null) {
            return;
        }
        metrics.recordReject(state, "text_frame");
        closeWithError(session, state, ErrorCode.ERROR_CODE_INVALID_MESSAGE, "Realtime WebSocket accepts binary protobuf frames only",
            RealtimeCloseCode.REALTIME_CLOSE_CODE_MALFORMED_FRAME);
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) throws Exception {
        RealtimeConnectionState state = existingState(session);
        if (state == null) {
            return;
        }
        connectionCleanup.recordTransportError(state, exception);
        if (session.isOpen()) {
            sessionOperations.requestClose(session, state, TRANSPORT_FAILURE, "transport_error");
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        releaseUnauthenticatedSlot(session);
        connectionCleanup.unregister(session, status);
    }

    private void handleHandshake(WebSocketSession session, RealtimeConnectionState state, RealtimeEnvelope envelope) {
        if (envelope.getPayloadCase() != RealtimeEnvelope.PayloadCase.CLIENT_HELLO) {
            metrics.recordReject(state, "missing_client_hello");
            closeWithError(session, state, ErrorCode.ERROR_CODE_UNAUTHORIZED, "ClientHello is required before other realtime messages",
                RealtimeCloseCode.REALTIME_CLOSE_CODE_PROTOCOL_VIOLATION);
            return;
        }

        ClientHello hello = envelope.getClientHello();
        int requestedProtocolVersion = hello.getProtocolVersion() != 0
            ? hello.getProtocolVersion()
            : envelope.getProtocolVersion();
        if (requestedProtocolVersion != properties.getProtocolVersion()) {
            metrics.recordReject(state, "unsupported_protocol");
            closeWithError(session, state, ErrorCode.ERROR_CODE_UNSUPPORTED_PROTOCOL, "Unsupported realtime protocol version",
                RealtimeCloseCode.REALTIME_CLOSE_CODE_UNSUPPORTED_PROTOCOL);
            return;
        }

        RealtimePrincipal principal;
        try {
            principal = authenticator.authenticate(session.getHandshakeHeaders(), requestOrigin(session), hello);
        } catch (RealtimeAuthenticationException exception) {
            metrics.recordAuthFailure(exception.userMessage());
            closeForAuthenticationFailure(session, state, exception);
            return;
        }

        if (principal.isMinecraft() && !admittedToNetwork(session, state, principal)) {
            return;
        }

        state.authenticate(principal);
        connectionRegistry.onAuthenticated(session);
        releaseUnauthenticatedSlot(session);
        for (Topic topic : hello.getSupportedTopicsList()) {
            if (topicAuthorizer.canSubscribe(principal, topic)) {
                state.subscribe(topic);
            } else {
                metrics.recordTopicAuthorizationFailure(state, topic, "handshake");
            }
        }

        sessionOperations.deliver(session, state, codec.serverHello(state.getConnectionId(), state.getSubscriptions()));
        if (principal.isMinecraft()) {
            networkHub.welcome(state);
        }
    }

    private boolean admittedToNetwork(WebSocketSession session, RealtimeConnectionState state, RealtimePrincipal principal) {
        return switch (networkHub.admit(session, state, principal)) {
            case ADMITTED -> true;
            case NETWORK_FULL -> {
                metrics.recordReject(state, "network_full");
                closeWithError(session, state, ErrorCode.ERROR_CODE_INTERNAL, "The modl network already has its maximum of servers",
                    RealtimeCloseCode.REALTIME_CLOSE_CODE_OVERLOADED);
                yield false;
            }
            case KEY_REVOKED -> {
                metrics.recordReject(state, "server_key_revoked");
                closeWithError(session, state, ErrorCode.ERROR_CODE_UNAUTHORIZED, "The server key was revoked",
                    RealtimeCloseCode.REALTIME_CLOSE_CODE_AUTHENTICATION_FAILED);
                yield false;
            }
        };
    }

    private boolean admitInbound(WebSocketSession session, RealtimeConnectionState state) {
        if (!state.isAuthenticated() || !state.getPrincipal().isMinecraft()) {
            if (rateLimiter.tryAcquire(state)) {
                return true;
            }
            metrics.recordReject(state, "rate_limited");
            closeWithError(session, state, ErrorCode.ERROR_CODE_RATE_LIMITED, RATE_LIMITED_MESSAGE,
                RealtimeCloseCode.REALTIME_CLOSE_CODE_RATE_LIMITED);
            return false;
        }
        return switch (pluginBudget.admit(state)) {
            case ADMIT -> true;
            case DROP_SILENTLY -> false;
            case DROP_AND_WARN -> {
                metrics.recordReject(state, "rate_limited");
                sessionOperations.deliver(session, state, codec.error(ErrorCode.ERROR_CODE_RATE_LIMITED, RATE_LIMITED_MESSAGE));
                yield false;
            }
            case CLOSE -> {
                metrics.recordReject(state, "rate_limited");
                closeWithError(session, state, ErrorCode.ERROR_CODE_RATE_LIMITED, RATE_LIMITED_MESSAGE,
                    RealtimeCloseCode.REALTIME_CLOSE_CODE_RATE_LIMITED);
                yield false;
            }
        };
    }

    @Nullable
    private static HttpOrigin requestOrigin(WebSocketSession session) {
        return session.getAttributes().get(RealtimeSessionAttributes.REQUEST_ORIGIN) instanceof HttpOrigin origin ? origin : null;
    }

    private void releaseUnauthenticatedSlot(WebSocketSession session) {
        Map<String, Object> attributes = session.getAttributes();
        if (attributes == null) {
            return;
        }
        if (attributes.get(RealtimeSessionAttributes.UNAUTHENTICATED_SLOT) instanceof RealtimeUnauthenticatedSlot slot) {
            slot.releaseOnce(connectionLimiter);
        }
    }

    private @Nullable RealtimeConnectionState stateForIncomingFrame(WebSocketSession session) {
        Optional<RealtimeConnectionState> existing = connectionRegistry.get(session);
        if (existing.isPresent()) {
            return existing.get();
        }
        return connectionRegistry.isTerminal(session) ? null : connectionRegistry.register(session);
    }

    private @Nullable RealtimeConnectionState existingState(WebSocketSession session) {
        return connectionRegistry.get(session).orElse(null);
    }

    private void handleAuthenticatedMessage(WebSocketSession session, RealtimeConnectionState state, RealtimeEnvelope envelope) {
        if (envelope.getProtocolVersion() != 0 && envelope.getProtocolVersion() != properties.getProtocolVersion()) {
            metrics.recordReject(state, "unsupported_protocol");
            closeWithError(session, state, ErrorCode.ERROR_CODE_UNSUPPORTED_PROTOCOL, "Unsupported realtime protocol version",
                RealtimeCloseCode.REALTIME_CLOSE_CODE_UNSUPPORTED_PROTOCOL);
            return;
        }

        switch (envelope.getPayloadCase()) {
            case HEARTBEAT -> state.recordHeartbeat();
            case ACK -> metrics.recordAck(state, envelope.getAck().getEventId());
            case SUBSCRIBE -> subscribe(state, envelope.getSubscribe().getTopicsList());
            case UNSUBSCRIBE -> unsubscribe(state, envelope.getUnsubscribe().getTopicsList());
            case CLIENT_HELLO -> {
                metrics.recordReject(state, "duplicate_client_hello");
                closeWithError(session, state, ErrorCode.ERROR_CODE_INVALID_MESSAGE, "ClientHello may only be sent once",
                    RealtimeCloseCode.REALTIME_CLOSE_CODE_PROTOCOL_VIOLATION);
            }
            default -> {
                if (state.getPrincipal().isMinecraft() && NetworkHub.handles(envelope.getPayloadCase())) {
                    acceptNetworkMessage(session, state, envelope);
                    return;
                }
                metrics.recordReject(state, "unsupported_message");
                closeWithError(session, state, ErrorCode.ERROR_CODE_INVALID_MESSAGE, "Unsupported realtime message type",
                    RealtimeCloseCode.REALTIME_CLOSE_CODE_PROTOCOL_VIOLATION);
            }
        }
    }

    private void acceptNetworkMessage(WebSocketSession session, RealtimeConnectionState state, RealtimeEnvelope envelope) {
        if (networkHub.accept(state, envelope) == NetworkHub.Disposition.FORBIDDEN) {
            metrics.recordReject(state, "network_role");
            closeWithError(session, state, ErrorCode.ERROR_CODE_FORBIDDEN, "This server may not send that network message",
                RealtimeCloseCode.REALTIME_CLOSE_CODE_PROTOCOL_VIOLATION);
        }
    }

    private void subscribe(RealtimeConnectionState state, List<Topic> topics) {
        for (Topic topic : topics) {
            if (topic == Topic.TOPIC_UNSPECIFIED || topic == Topic.UNRECOGNIZED) {
                metrics.recordTopicAuthorizationFailure(state, topic, "subscribe");
                continue;
            }
            if (topicAuthorizer.canSubscribe(state.getPrincipal(), topic)) {
                state.subscribe(topic);
            } else {
                metrics.recordTopicAuthorizationFailure(state, topic, "subscribe");
            }
        }
    }

    private void unsubscribe(RealtimeConnectionState state, List<Topic> topics) {
        for (Topic topic : topics) {
            if (topic == Topic.TOPIC_UNSPECIFIED || topic == Topic.UNRECOGNIZED) {
                continue;
            }
            state.unsubscribe(topic);
        }
    }

    private void closeForAuthenticationFailure(WebSocketSession session, RealtimeConnectionState state,
                                               RealtimeAuthenticationException exception) {
        Optional<String> serverVersion = exception.serverVersion();
        if (serverVersion.isPresent()) {
            closeWithFrame(session, state, codec.versionMismatch(exception.userMessage(), serverVersion.get()),
                RealtimeCloseStatuses.of(RealtimeCloseCode.REALTIME_CLOSE_CODE_VERSION_MISMATCH, exception.userMessage()));
            return;
        }
        closeWithError(session, state, exception.getErrorCode(), exception.userMessage(),
            RealtimeCloseCode.REALTIME_CLOSE_CODE_AUTHENTICATION_FAILED);
    }

    private void closeWithError(WebSocketSession session, RealtimeConnectionState state, ErrorCode code, String message,
                                RealtimeCloseCode closeCode) {
        closeWithFrame(session, state, codec.error(code, message), RealtimeCloseStatuses.of(closeCode, message));
    }

    private void closeWithFrame(WebSocketSession session, RealtimeConnectionState state, BinaryMessage frame,
                                CloseStatus status) {
        if (session.isOpen()) {
            sessionOperations.sendThenClose(session, state, frame, status, "error");
            return;
        }
        sessionOperations.requestClose(session, state, status, "error");
    }
}

package gg.modl.backend.realtime.transport;

import gg.modl.backend.realtime.lifecycle.RealtimeConnectionCleanup;
import gg.modl.backend.realtime.metrics.RealtimeMetrics;
import gg.modl.backend.realtime.state.RealtimeConnectionRegistry;
import gg.modl.backend.realtime.state.RealtimeConnectionState;
import gg.modl.backend.realtime.state.RealtimeOutbox;
import gg.modl.proto.modl.v1.RealtimeCloseCode;
import java.io.IOException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;

@Component
@RequiredArgsConstructor
public class RealtimeSessionOperations {
    public static final CloseStatus SLOW_CONSUMER = RealtimeCloseStatuses.of(
        RealtimeCloseCode.REALTIME_CLOSE_CODE_SLOW_CONSUMER, "Realtime client is not reading its messages");
    public static final CloseStatus OVERLOADED = RealtimeCloseStatuses.of(
        RealtimeCloseCode.REALTIME_CLOSE_CODE_OVERLOADED, "Realtime backend has no free outbound worker");
    public static final CloseStatus SEND_FAILED = RealtimeCloseStatuses.of(
        RealtimeCloseCode.REALTIME_CLOSE_CODE_TRANSPORT_FAILURE, "Realtime message could not be sent");
    private static final String SLOW_CONSUMER_REASON = "slow_consumer";

    private final RealtimeConnectionRegistry connectionRegistry;
    private final RealtimeConnectionCleanup connectionCleanup;
    private final RealtimeMetrics metrics;
    private final RealtimeOutboundExecutor outboundExecutor;

    private enum SendOutcome { SENT, CLOSED, FAILED }

    public boolean deliver(WebSocketSession session, RealtimeConnectionState state, BinaryMessage message) {
        return admitted(session, state, state.outbox().offer(message));
    }

    public boolean deliverLatest(WebSocketSession session, RealtimeConnectionState state, String slot, BinaryMessage message) {
        return admitted(session, state, state.outbox().offerLatest(slot, message));
    }

    public void sendThenClose(WebSocketSession session, RealtimeConnectionState state, BinaryMessage message,
                              CloseStatus status, String metricReason) {
        state.markClosing();
        admitted(session, state, state.outbox().offerThenClose(message, status, metricReason));
    }

    public boolean requestClose(WebSocketSession session, RealtimeConnectionState state, CloseStatus status, String metricReason) {
        WebSocketSession outboundSession = connectionRegistry.getSession(session).orElse(session);
        state.outbox().close();
        state.markClosing();
        state.markTerminal();
        connectionRegistry.markTerminal(session);
        connectionRegistry.markTerminal(outboundSession);
        if (!outboundSession.isOpen()) {
            connectionCleanup.unregister(outboundSession, status);
            return true;
        }
        try {
            outboundSession.close(status);
            metrics.recordReconnectClose(state, metricReason);
        } catch (IOException | RuntimeException exception) {
            metrics.recordReconnectClose(state, metricReason + "_close_failed");
        }
        if (!outboundSession.isOpen()) {
            connectionCleanup.unregister(outboundSession, status);
            return true;
        }
        return false;
    }

    private boolean admitted(WebSocketSession session, RealtimeConnectionState state, RealtimeOutbox.Admission admission) {
        return switch (admission) {
            case STARTS_DRAIN -> startDrain(session, state);
            case QUEUED -> true;
            case FULL -> {
                closeSlowConsumer(session, state);
                yield false;
            }
            case CLOSED -> false;
        };
    }

    private boolean startDrain(WebSocketSession session, RealtimeConnectionState state) {
        if (outboundExecutor.tryExecute(() -> drain(session, state))) {
            return true;
        }
        requestClose(session, state, OVERLOADED, "outbox_rejected");
        return false;
    }

    private void closeSlowConsumer(WebSocketSession session, RealtimeConnectionState state) {
        state.outbox().close();
        state.markClosing();
        if (!outboundExecutor.tryExecute(() -> requestClose(session, state, SLOW_CONSUMER, SLOW_CONSUMER_REASON))) {
            requestClose(session, state, SLOW_CONSUMER, SLOW_CONSUMER_REASON);
        }
    }

    private void drain(WebSocketSession session, RealtimeConnectionState state) {
        for (RealtimeOutbox.Outbound next = state.outbox().next(); next != null; next = state.outbox().next()) {
            boolean keepDraining = switch (next) {
                case RealtimeOutbox.Close close -> {
                    requestClose(session, state, close.status(), close.reason());
                    yield false;
                }
                case RealtimeOutbox.Send queued -> sendQueued(session, state, queued.message());
            };
            if (!keepDraining) {
                return;
            }
        }
    }

    private boolean sendQueued(WebSocketSession session, RealtimeConnectionState state, BinaryMessage message) {
        SendOutcome outcome = send(session, state, message);
        if (outcome == SendOutcome.FAILED) {
            requestClose(session, state, SEND_FAILED, "send_failed");
            return false;
        }
        if (outcome == SendOutcome.CLOSED) {
            state.outbox().close();
            return false;
        }
        return true;
    }

    private SendOutcome send(WebSocketSession session, RealtimeConnectionState state, BinaryMessage message) {
        WebSocketSession outboundSession = connectionRegistry.getSession(session).orElse(session);
        if (connectionRegistry.isTerminal(session) || connectionRegistry.isTerminal(outboundSession)) {
            return SendOutcome.CLOSED;
        }
        if (!outboundSession.isOpen()) {
            connectionCleanup.unregister(outboundSession, CloseStatus.NO_CLOSE_FRAME);
            return SendOutcome.CLOSED;
        }
        try {
            outboundSession.sendMessage(message);
            metrics.recordTransportSendSuccess(state);
            return SendOutcome.SENT;
        } catch (IOException | RuntimeException exception) {
            metrics.recordTransportSendFailure(state, exception);
            return SendOutcome.FAILED;
        }
    }
}

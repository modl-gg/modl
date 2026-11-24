package gg.modl.backend.network;

import gg.modl.backend.realtime.transport.RealtimeCodec;
import gg.modl.backend.realtime.transport.RealtimeSessionOperations;
import gg.modl.proto.modl.v1.ErrorCode;
import gg.modl.proto.modl.v1.RealtimeEnvelope;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;

@Component
@RequiredArgsConstructor
public class NetworkOutbound {
    private final RealtimeCodec codec;
    private final RealtimeSessionOperations sessionOperations;

    byte[] prepare(RealtimeEnvelope envelope) {
        return codec.relayedPayload(envelope);
    }

    boolean send(NetworkSession target, RealtimeEnvelope envelope) {
        return send(target, prepare(envelope));
    }

    boolean send(NetworkSession target, byte[] payload) {
        if (!target.isOpen()) {
            return false;
        }
        return sessionOperations.deliver(target.socket(), target.state(), new BinaryMessage(payload));
    }

    boolean sendLatest(NetworkSession target, String slot, byte[] payload) {
        if (!target.isOpen()) {
            return false;
        }
        return sessionOperations.deliverLatest(target.socket(), target.state(), slot, new BinaryMessage(payload));
    }

    void close(NetworkSession target, ErrorCode code, String message, CloseStatus status, String reason) {
        sessionOperations.sendThenClose(target.socket(), target.state(), codec.error(code, message), status, reason);
    }

    void end(NetworkSession target, CloseStatus status, String reason) {
        sessionOperations.requestClose(target.socket(), target.state(), status, reason);
    }
}

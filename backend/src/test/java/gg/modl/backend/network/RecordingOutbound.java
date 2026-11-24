package gg.modl.backend.network;

import com.google.protobuf.InvalidProtocolBufferException;
import gg.modl.proto.modl.v1.ErrorCode;
import gg.modl.proto.modl.v1.RealtimeEnvelope;
import java.util.ArrayList;
import java.util.List;
import org.springframework.web.socket.CloseStatus;

final class RecordingOutbound extends NetworkOutbound {
    final List<Sent> sent = new ArrayList<>();
    final List<String> closed = new ArrayList<>();
    final List<Ended> ended = new ArrayList<>();

    RecordingOutbound() {
        super(null, null);
    }

    @Override
    byte[] prepare(RealtimeEnvelope envelope) {
        return envelope.toByteArray();
    }

    @Override
    boolean send(NetworkSession target, RealtimeEnvelope envelope) {
        sent.add(new Sent(target.connectionId(), null, envelope));
        return true;
    }

    @Override
    boolean send(NetworkSession target, byte[] payload) {
        sent.add(new Sent(target.connectionId(), null, parse(payload)));
        return true;
    }

    @Override
    boolean sendLatest(NetworkSession target, String slot, byte[] payload) {
        sent.add(new Sent(target.connectionId(), slot, parse(payload)));
        return true;
    }

    @Override
    void close(NetworkSession target, ErrorCode code, String message, CloseStatus status, String reason) {
        closed.add(target.connectionId());
    }

    @Override
    void end(NetworkSession target, CloseStatus status, String reason) {
        target.state().markClosing();
        ended.add(new Ended(target.connectionId(), status.getCode()));
    }

    List<RealtimeEnvelope> to(String connectionId) {
        return sent.stream().filter(entry -> entry.connectionId().equals(connectionId)).map(Sent::envelope).toList();
    }

    private static RealtimeEnvelope parse(byte[] payload) {
        try {
            return RealtimeEnvelope.parseFrom(payload);
        } catch (InvalidProtocolBufferException malformed) {
            throw new IllegalStateException(malformed);
        }
    }

    record Sent(String connectionId, String slot, RealtimeEnvelope envelope) {
    }

    record Ended(String connectionId, int closeCode) {
    }
}

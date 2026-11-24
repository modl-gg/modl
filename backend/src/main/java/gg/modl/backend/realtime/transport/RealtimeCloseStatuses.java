package gg.modl.backend.realtime.transport;

import gg.modl.proto.modl.v1.RealtimeCloseCode;
import org.springframework.web.socket.CloseStatus;

public final class RealtimeCloseStatuses {
    private RealtimeCloseStatuses() {
    }

    public static CloseStatus of(RealtimeCloseCode code, String reason) {
        return new CloseStatus(code.getNumber(), reason);
    }
}

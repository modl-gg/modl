package gg.modl.minecraft.core.realtime;

import gg.modl.minecraft.core.support.RecordingPluginLogger;
import gg.modl.minecraft.core.support.TestConnections;
import gg.modl.proto.modl.v1.Heartbeat;
import gg.modl.proto.modl.v1.RealtimeCloseCode;
import gg.modl.proto.modl.v1.RealtimeEnvelope;
import gg.modl.proto.modl.v1.RealtimeEnvelope.PayloadCase;
import gg.modl.proto.modl.v1.Topic;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RealtimeConnectionTest {
    private static final RealtimeInbound IGNORE = envelope -> { };

    @Test
    void onlyClosesThatCannotSucceedOnRetryEndRealtime() {
        assertEquals(Optional.of(TerminationReason.DISABLED_BY_BACKEND),
                RealtimeConnection.terminationForClose(RealtimeCloseCode.REALTIME_CLOSE_CODE_DISABLED.getNumber()));
        assertEquals(Optional.of(TerminationReason.CREDENTIALS_REJECTED),
                RealtimeConnection.terminationForClose(RealtimeCloseCode.REALTIME_CLOSE_CODE_AUTHENTICATION_FAILED.getNumber()));
        assertEquals(Optional.of(TerminationReason.PROTOCOL_UNSUPPORTED),
                RealtimeConnection.terminationForClose(RealtimeCloseCode.REALTIME_CLOSE_CODE_UNSUPPORTED_PROTOCOL.getNumber()));
        assertEquals(Optional.of(TerminationReason.PROTOCOL_VIOLATION),
                RealtimeConnection.terminationForClose(RealtimeCloseCode.REALTIME_CLOSE_CODE_PROTOCOL_VIOLATION.getNumber()));
    }

    @Test
    void transientClosesReconnect() {
        for (RealtimeCloseCode code : new RealtimeCloseCode[] {
                RealtimeCloseCode.REALTIME_CLOSE_CODE_SLOW_CONSUMER, RealtimeCloseCode.REALTIME_CLOSE_CODE_OVERLOADED,
                RealtimeCloseCode.REALTIME_CLOSE_CODE_TRANSPORT_FAILURE, RealtimeCloseCode.REALTIME_CLOSE_CODE_HEARTBEAT_TIMEOUT,
                RealtimeCloseCode.REALTIME_CLOSE_CODE_HANDSHAKE_TIMEOUT, RealtimeCloseCode.REALTIME_CLOSE_CODE_DEPLOY_DRAIN,
                RealtimeCloseCode.REALTIME_CLOSE_CODE_RATE_LIMITED, RealtimeCloseCode.REALTIME_CLOSE_CODE_MALFORMED_FRAME,
                RealtimeCloseCode.REALTIME_CLOSE_CODE_FRAME_TOO_LARGE}) {
            assertEquals(Optional.empty(), RealtimeConnection.terminationForClose(code.getNumber()), code.name());
        }
        for (int standardCode : new int[] {1000, 1001, 1006, 1008, 1011, 1012, 1013}) {
            assertEquals(Optional.empty(), RealtimeConnection.terminationForClose(standardCode), String.valueOf(standardCode));
        }
    }

    @Test
    void advisedReconnectDelayIsCappedForDeployDrainAdvice() {
        assertEquals(7_000, RealtimeConnection.capAdvisedReconnectDelayMs(7_000));
        assertEquals(60_000, RealtimeConnection.capAdvisedReconnectDelayMs(120_000));
        assertEquals(0, RealtimeConnection.capAdvisedReconnectDelayMs(-1));
    }

    @Test
    void transportPayloadsCannotBeRegisteredAsInbound() {
        RealtimeConnection connection = connection();
        try {
            assertThrows(IllegalArgumentException.class, () -> connection.register(PayloadCase.SERVER_HELLO, IGNORE));
            assertThrows(IllegalArgumentException.class, () -> connection.register(PayloadCase.HEARTBEAT, IGNORE));
            assertThrows(IllegalArgumentException.class, () -> connection.register(PayloadCase.RECONNECT_ADVICE, IGNORE));
            assertThrows(IllegalArgumentException.class, () -> connection.register(PayloadCase.ERROR, IGNORE));
        } finally {
            connection.stop();
        }
    }

    @Test
    void aPayloadCaseHasOneInboundHandler() {
        RealtimeConnection connection = connection();
        try {
            connection.register(PayloadCase.PUNISHMENT_PUSH, IGNORE);

            assertThrows(IllegalStateException.class, () -> connection.register(PayloadCase.PUNISHMENT_PUSH, IGNORE));
        } finally {
            connection.stop();
        }
    }

    @Test
    void sendRefusesWhileNotConnected() {
        RealtimeConnection connection = connection();
        try {
            assertFalse(connection.send(RealtimeEnvelope.newBuilder()
                    .setHeartbeat(Heartbeat.getDefaultInstance()).build()));
        } finally {
            connection.stop();
        }
    }

    private static RealtimeConnection connection() {
        return new RealtimeConnection(TestConnections.to("http://127.0.0.1:18080", "key"), 1,
                Collections.singleton(Topic.TOPIC_MINECRAFT_PERMISSIONS), () -> "Server 1", null,
                new RecordingPluginLogger(), false);
    }
}

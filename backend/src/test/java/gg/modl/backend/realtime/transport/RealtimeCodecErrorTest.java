package gg.modl.backend.realtime.transport;

import com.google.protobuf.InvalidProtocolBufferException;
import gg.modl.backend.realtime.config.RealtimeProperties;
import gg.modl.proto.modl.v1.ErrorCode;
import gg.modl.proto.modl.v1.RealtimeEnvelope;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.BinaryMessage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class RealtimeCodecErrorTest {
    private final RealtimeCodec codec = new RealtimeCodec(new RealtimeProperties());

    @Test
    void aVersionMismatchCarriesTheServerVersionAsItsOwnField() throws InvalidProtocolBufferException {
        RealtimeEnvelope envelope = parse(codec.versionMismatch("Version mismatch: modl server 9.0.1", "9.0.1"));

        assertEquals(ErrorCode.ERROR_CODE_VERSION_MISMATCH, envelope.getError().getCode());
        assertEquals("9.0.1", envelope.getError().getServerVersion());
    }

    @Test
    void otherErrorsCarryNoServerVersion() throws InvalidProtocolBufferException {
        RealtimeEnvelope envelope = parse(codec.error(ErrorCode.ERROR_CODE_FORBIDDEN, "This server may not send that network message"));

        assertEquals(ErrorCode.ERROR_CODE_FORBIDDEN, envelope.getError().getCode());
        assertFalse(envelope.getError().hasServerVersion());
    }

    private static RealtimeEnvelope parse(BinaryMessage message) throws InvalidProtocolBufferException {
        return RealtimeEnvelope.parseFrom(message.getPayload());
    }
}

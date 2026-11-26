package gg.modl.minecraft.replay.format;

import gg.modl.proto.modl.v1.ReplayEnd;
import gg.modl.proto.modl.v1.ReplayFrame;
import gg.modl.proto.modl.v1.ReplayHeader;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ReplayFileWriterTest {
    @Test
    void framesRoundTripBehindTheMagicAndVersion() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ReplayFrame header = ReplayFrame.newBuilder().setHeader(ReplayHeader.newBuilder().setFormatVersion(5).setReplayId("r")).build();
        ReplayFrame end = ReplayFrame.newBuilder().setEnd(ReplayEnd.newBuilder().setDurationMs(42)).build();
        try (ReplayFileWriter writer = new ReplayFileWriter(out)) {
            writer.write(header);
            writer.write(end);
        }
        byte[] file = out.toByteArray();
        assertArrayEquals(new byte[]{'M', 'O', 'D', 'L', 0, 5}, Arrays.copyOf(file, 6));
        try (ReplayFileReader reader = ReplayFileReader.open(new ByteArrayInputStream(file), ReplayLimits.MAX_UNCOMPRESSED_BYTES)) {
            assertEquals(header, reader.next());
            assertEquals(end, reader.next());
            assertNull(reader.next());
        }
    }

    @Test
    void anotherFormatIsRefused() {
        byte[] gzip = {0x1f, (byte) 0x8b, 8, 0, 0, 0, 0, 0};
        assertThrows(ReplayFormatException.class, () -> ReplayFileReader.open(new ByteArrayInputStream(gzip), ReplayLimits.MAX_UNCOMPRESSED_BYTES));
    }
}

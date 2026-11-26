package gg.modl.minecraft.replay.format;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BoundedInputStreamTest {
    @Test
    void aStreamOfExactlyTheLimitEndsNormally() throws IOException {
        try (InputStream bounded = new BoundedInputStream(new ByteArrayInputStream(new byte[]{1, 2, 3, 4}), 4)) {
            assertEquals(4, bounded.read(new byte[8], 0, 8));
            assertEquals(-1, bounded.read(new byte[8], 0, 8));
            assertEquals(-1, bounded.read());
        }
    }

    @Test
    void aSingleByteReaderAlsoEndsAtExactlyTheLimit() throws IOException {
        try (InputStream bounded = new BoundedInputStream(new ByteArrayInputStream(new byte[]{1, 2}), 2)) {
            assertEquals(1, bounded.read());
            assertEquals(2, bounded.read());
            assertEquals(-1, bounded.read());
        }
    }

    @Test
    void aByteBeyondTheLimitIsAFormatProblem() throws IOException {
        try (InputStream bounded = new BoundedInputStream(new ByteArrayInputStream(new byte[]{1, 2, 3, 4, 5}), 4)) {
            assertEquals(4, bounded.read(new byte[8], 0, 8));
            assertThrows(ReplayFormatException.class, () -> bounded.read(new byte[8], 0, 8));
        }
    }
}

package gg.modl.minecraft.replay.format;

import gg.modl.proto.modl.v1.ReplayFrame;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReplayFixturesTest {
    @ParameterizedTest
    @ValueSource(strings = {"latest", "1.21", "legacy"})
    void everyCapturedFixtureIsACompleteVersionFiveReplay(String line) throws IOException {
        try (InputStream file = ReplayFixturesTest.class.getResourceAsStream("/fixtures/v5/" + line + ".modlreplay")) {
            assertNotNull(file, line);
            try (ReplayFileReader reader = ReplayFileReader.open(file, ReplayLimits.MAX_UNCOMPRESSED_BYTES)) {
                ReplayFrame first = reader.next();
                assertNotNull(first);
                assertTrue(first.hasHeader());
                assertEquals(ReplayFileFormat.FORMAT_VERSION, first.getHeader().getFormatVersion());
                ReplayFrame last = first;
                int sections = 0;
                for (ReplayFrame frame = reader.next(); frame != null; frame = reader.next()) {
                    if (frame.hasSection()) sections++;
                    last = frame;
                }
                assertTrue(last.hasEnd(), line + " ends with the end frame");
                assertEquals(last.getEnd().getSectionCount(), sections);
            }
        }
    }
}

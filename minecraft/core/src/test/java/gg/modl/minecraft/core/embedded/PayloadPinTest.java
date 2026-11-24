package gg.modl.minecraft.core.embedded;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PayloadPinTest {
    @Test
    void readsTheFormatTheBuildWrites() throws IOException {
        PayloadPin pin = PayloadPin.parse(fixture());

        assertEquals("3.0.0", pin.version().toString());
        assertEquals("modl-embedded-3.0.0.zip", pin.artifact().file());
        assertEquals(repeat('5', 64), pin.artifact().sha256());
        assertEquals(12_345_678, pin.artifact().size());
    }

    @Test
    void aMalformedPinNamesTheFileAndTheRemedy() throws IOException {
        String fixture = fixture();
        for (String broken : new String[] {fixture.replace("sha256 ", "sha "), fixture.replace("version 3.0.0", "version 3.0"),
                fixture.replace("size 12345678", "size 0"), fixture.replace(PayloadPin.HEADER, "modl-embedded-payload 2")}) {
            IOException refused = assertThrows(IOException.class, () -> PayloadPin.parse(broken));
            assertTrue(refused.getMessage().contains(PayloadPin.RESOURCE), refused.getMessage());
            assertTrue(refused.getMessage().contains("Download the full modl plugin jar"), refused.getMessage());
        }
    }

    @Test
    void aPluginJarWithoutAPinFailsWithARemedy() {
        IOException refused = assertThrows(IOException.class, () -> PayloadPin.bundledIn(new ClassLoader(null) { }));

        assertTrue(refused.getMessage().contains("Download the full modl plugin jar"), refused.getMessage());
    }

    private static String fixture() throws IOException {
        try (InputStream input = PayloadPinTest.class.getResourceAsStream("payload-pin.txt")) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static String repeat(char character, int count) {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < count; i++) text.append(character);
        return text.toString();
    }
}
